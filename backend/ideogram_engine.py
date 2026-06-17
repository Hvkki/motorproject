"""
Двигун генерації зображень на базі Ideogram 4 (nf4) для 2x NVIDIA T4.

This module owns everything related to the model:

* Loading the gated `ideogram-4-nf4-diffusers` checkpoint.
* Splitting it across TWO T4 GPUs so the big Qwen3-VL-8B text encoder lives on
  cuda:1 while the DiT transformer + VAE live on cuda:0. This is what lets a
  9.3B model + an 8B text encoder fit on 2x16GB cards.
* Text-to-image generation (batch of N images at once).
* "Circle to modify" inpainting (regenerate only a masked region).

If no CUDA GPU is available (e.g. this dev sandbox) OR MOCK_MODE=1, the engine
falls back to a pure-PIL MOCK generator. The MOCK generator produces pleasant
placeholder art so the entire UI + API flow can be exercised without a GPU.
The public API (`generate`, `inpaint`) is identical in both modes.
"""

from __future__ import annotations

import base64
import hashlib
import io
import math
import random
import threading
import time
from dataclasses import dataclass
from typing import Optional

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Reduce CUDA fragmentation OOM on the offload fallback. Must be set before
# torch initialises CUDA, so it lives at module import time.
import os
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")

from config import settings


# --------------------------------------------------------------------------- #
#  Result container
# --------------------------------------------------------------------------- #
@dataclass
class GenResult:
    images_b64: list[str]
    seeds: list[int]
    elapsed: float
    mock: bool
    width: int
    height: int


def _pil_to_b64(img: Image.Image, fmt: str = "PNG") -> str:
    buf = io.BytesIO()
    img.save(buf, format=fmt)
    return "data:image/{};base64,{}".format(
        fmt.lower(), base64.b64encode(buf.getvalue()).decode("ascii")
    )


def _b64_to_pil(data: str) -> Image.Image:
    if "," in data:
        data = data.split(",", 1)[1]
    return Image.open(io.BytesIO(base64.b64decode(data))).convert("RGB")


# --------------------------------------------------------------------------- #
#  Engine
# --------------------------------------------------------------------------- #
class IdeogramEngine:
    """Thread-safe wrapper around the Ideogram 4 pipeline (or the mock)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()       # GPU work is serialised
        self._load_lock = threading.Lock()  # ensures the model loads only once
        self._pipe = None                   # diffusers pipeline (real mode)
        self._inpaint_pipe = None
        self._img2img_pipe = None
        self._torch = None
        self._loaded = False
        self.mock = True                    # decided in load()
        self.device_info: dict = {}

    # ---- lifecycle -------------------------------------------------------- #
    def load(self) -> None:
        """Decide between real and mock mode, then load if real (once)."""
        if self._loaded:
            return
        # Guard so concurrent health/generate requests don't all trigger a
        # heavy load at the same time (that was spamming the logs + wasting VRAM).
        with self._load_lock:
            if self._loaded:
                return
            self._load_impl()

    def _load_impl(self) -> None:
        if settings.force_mock:
            self.mock = True
            self.device_info = {"mode": "mock", "reason": "MOCK_MODE=1"}
            self._loaded = True
            return

        try:
            import torch  # noqa: WPS433  (lazy import on purpose)

            self._torch = torch
            if not torch.cuda.is_available():
                self.mock = True
                self.device_info = {"mode": "mock", "reason": "no CUDA device"}
                self._loaded = True
                return
        except Exception as exc:  # torch missing -> sandbox
            self.mock = True
            self.device_info = {"mode": "mock", "reason": f"torch unavailable: {exc}"}
            self._loaded = True
            return

        # We have CUDA -> load the real pipeline. If anything goes wrong
        # (deps, gated weights, OOM), degrade to MOCK so the app still serves.
        try:
            self._load_real()
        except Exception as exc:  # noqa: BLE001
            import traceback
            traceback.print_exc()
            print(f"[engine] real load FAILED -> falling back to MOCK mode: {exc}")
            self.mock = True
            self.device_info = {"mode": "mock", "reason": f"load failed: {exc}"}
        self._loaded = True

    def _load_real(self) -> None:
        torch = self._torch
        self._preload_cuda_libs()
        n_gpus = torch.cuda.device_count()
        names = [torch.cuda.get_device_name(i) for i in range(n_gpus)]
        print(f"[engine] CUDA GPUs detected: {n_gpus} -> {names}")
        self._apply_bnb_shim()
        PipeCls = self._pipeline_class()

        common = dict(torch_dtype=torch.float16)  # T4 = fp16
        if settings.hf_token:
            common["token"] = settings.hf_token

        pipe = self._load_balanced(PipeCls, common, n_gpus, names)
        if pipe is None:
            pipe = self._load_offload(PipeCls, common, names)

        for attr in ("enable_attention_slicing", "enable_vae_slicing", "enable_vae_tiling"):
            fn = getattr(pipe, attr, None)
            if callable(fn):
                try:
                    fn()
                except Exception:  # noqa: BLE001
                    pass

        self._pipe = pipe
        self.mock = False
        print(f"[engine] ready: {self.device_info}")

    def _preload_cuda_libs(self) -> None:
        """Preload CUDA shared libs (esp. libnvJitLink) from the pip `nvidia-*`
        packages with RTLD_GLOBAL, so a CUDA-13 bitsandbytes can resolve
        `libnvJitLink.so.13` even on Kaggle's CUDA-12 base image."""
        import ctypes
        import glob
        import site

        roots = []
        try:
            roots += site.getsitepackages()
        except Exception:  # noqa: BLE001
            pass
        roots += ["/usr/local/lib/python3.12/dist-packages", "/usr/local/lib/python3.11/dist-packages"]
        patterns = ["nvidia/*/lib/libnvJitLink.so*", "nvidia/*/lib/libcudart.so*", "nvidia/*/lib/libcublas*.so*"]
        seen = set()
        for root in roots:
            for pat in patterns:
                for so in glob.glob(f"{root}/{pat}"):
                    if so in seen:
                        continue
                    seen.add(so)
                    try:
                        ctypes.CDLL(so, mode=ctypes.RTLD_GLOBAL)
                        print(f"[engine] preloaded {so.split('/')[-1]}")
                    except Exception:  # noqa: BLE001
                        pass

    def _apply_bnb_shim(self) -> None:
        """Some bnb builds return Params4bit.shape as a tuple; diffusers calls
        .numel() on it. math.prod handles both. Mirrors the official Space fix."""
        try:
            import math
            from diffusers.quantizers.bitsandbytes.bnb_quantizer import (
                BnB4BitDiffusersQuantizer,
            )

            def _cqps(self, param_name, current_param, loaded_param):
                n = math.prod(tuple(current_param.shape))
                inferred = (n,) if "bias" in param_name else ((n + 1) // 2, 1)
                if tuple(loaded_param.shape) != tuple(inferred):
                    raise ValueError(
                        f"Expected flattened shape of {param_name} to be "
                        f"{inferred}, got {tuple(loaded_param.shape)}."
                    )
                return True

            BnB4BitDiffusersQuantizer.check_quantized_param_shape = _cqps
            print("[engine] bitsandbytes shape shim applied")
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] bnb shim skipped: {exc}")

        # Recent transformers/accelerate forward an internal `_is_hf_initialized`
        # flag into the bitsandbytes Parameter constructors, which they don't
        # accept -> the device_map="balanced" multi-GPU load dies with
        # `Params4bit.__new__() got an unexpected keyword argument
        # '_is_hf_initialized'` and we wrongly fall back to single-GPU OOM.
        # Swallow the kwarg (and re-apply it as an attribute) so the 2-GPU split
        # works. See huggingface/transformers#43872.
        try:
            import bitsandbytes as bnb

            for _cls_name in ("Params4bit", "Int8Params"):
                _cls = getattr(bnb.nn, _cls_name, None)
                if _cls is None or getattr(_cls, "_kwarg_shimmed", False):
                    continue
                _orig_new = _cls.__new__

                def _patched_new(kls, *args, _orig_new=_orig_new, **kwargs):
                    hf_init = kwargs.pop("_is_hf_initialized", None)
                    obj = _orig_new(kls, *args, **kwargs)
                    if hf_init is not None:
                        try:
                            obj._is_hf_initialized = hf_init
                        except Exception:  # noqa: BLE001
                            pass
                    return obj

                _cls.__new__ = _patched_new
                _cls._kwarg_shimmed = True
            print("[engine] bitsandbytes _is_hf_initialized shim applied")
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] bnb kwarg shim skipped: {exc}")

    def _pipeline_class(self):
        try:
            from diffusers import Ideogram4Pipeline as PipeCls
            print("[engine] using Ideogram4Pipeline")
        except Exception as exc:  # noqa: BLE001
            from diffusers import DiffusionPipeline as PipeCls
            print(f"[engine] Ideogram4Pipeline unavailable ({exc}); using DiffusionPipeline")
        return PipeCls

    def _load_balanced(self, PipeCls, common, n_gpus, names):
        """Shard the quantized (nf4) model across BOTH T4s via device_map.

        accelerate places every component (both DiT branches, the Qwen3-VL text
        encoder, VAE) across the GPUs and adds cross-device transfer hooks. We
        KEEP nf4 (never .dequantize()) so it fits in 2x16GB.
        """
        if not (settings.dual_gpu and n_gpus >= 2):
            return None
        try:
            # IMPORTANT: do NOT give accelerate a "cpu" budget here. With a cpu
            # entry it offloads part of the nf4 model to CPU/meta, and bitsandbytes
            # 4bit params then crash at inference with
            #   NotImplementedError: Cannot copy out of meta tensor; no data!
            # (the hook tries to move a 4bit param whose quant_state.code is on
            # the meta device). bnb-4bit + accelerate CPU offload are incompatible.
            # The nf4 model (~16GB) fits across 2x T4 (~31GB) entirely on-GPU.
            max_memory = {i: "14GiB" for i in range(n_gpus)}
            print(f"[engine] loading {settings.model_repo} nf4, device_map=balanced {max_memory} (GPU-only, no CPU offload)…")
            pipe = PipeCls.from_pretrained(
                settings.model_repo, device_map="balanced", max_memory=max_memory, **common
            )
            self.device_info = {"mode": "cuda-balanced", "gpus": names}
            return pipe
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] device_map=balanced failed ({exc}); will try CPU offload")
            return None

    def _load_offload(self, PipeCls, common, names):
        """Fallback: single load + sequential CPU offload (slow but fits)."""
        print(f"[engine] loading {settings.model_repo} nf4 with CPU offload…")
        pipe = PipeCls.from_pretrained(settings.model_repo, **common)
        for method, mode in (
            ("enable_sequential_cpu_offload", "cuda-seq-offload"),
            ("enable_model_cpu_offload", "cuda-cpu-offload"),
        ):
            fn = getattr(pipe, method, None)
            if callable(fn):
                try:
                    fn()
                    self.device_info = {"mode": mode, "gpus": names}
                    return pipe
                except Exception:  # noqa: BLE001
                    continue
        pipe.to("cuda:0")
        self.device_info = {"mode": "cuda-single", "gpus": names}
        return pipe

    # ---- prompt helpers --------------------------------------------------- #
    def _expand_prompt(self, prompt: str) -> str:
        """
        Optionally turn a casual prompt into Ideogram's structured JSON caption
        via the free hosted "magic prompt" API. Falls back to the raw prompt
        on any error so generation never blocks on the network.
        """
        if not settings.magic_prompt_key:
            return prompt
        try:
            import requests

            resp = requests.post(
                "https://api.ideogram.ai/v1/magic-prompt",
                headers={"Api-Key": settings.magic_prompt_key},
                json={"prompt": prompt},
                timeout=15,
            )
            if resp.ok:
                return resp.json().get("prompt", prompt)
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] magic-prompt skipped: {exc}")
        return prompt

    # ---- public: text to image ------------------------------------------- #
    def generate(
        self,
        prompt: str,
        negative: str = "",
        n: int = 4,
        width: Optional[int] = None,
        height: Optional[int] = None,
        steps: Optional[int] = None,
        guidance: Optional[float] = None,
        seed: Optional[int] = None,
    ) -> GenResult:
        self.load()
        n = max(1, min(int(n), settings.max_batch))
        width = self._clamp_side(width or settings.default_width)
        height = self._clamp_side(height or settings.default_height)
        steps = int(steps or settings.default_steps)
        guidance = float(guidance if guidance is not None else settings.default_guidance)
        base_seed = int(seed) if seed is not None else random.randint(0, 2**31 - 1)
        seeds = [base_seed + i for i in range(n)]

        t0 = time.time()
        if self.mock:
            images = [self._mock_image(prompt, width, height, s) for s in seeds]
        else:
            images = self._generate_real(prompt, negative, seeds, width, height, steps, guidance)

        return GenResult(
            images_b64=[_pil_to_b64(im) for im in images],
            seeds=seeds,
            elapsed=round(time.time() - t0, 2),
            mock=self.mock,
            width=width,
            height=height,
        )

    def _generate_real(self, prompt, negative, seeds, width, height, steps, guidance):
        torch = self._torch
        prompt = self._expand_prompt(prompt)
        # Community-proven schedule: main CFG for the first ~70% of steps, then
        # drop to 3.0 for the final ~30% ("override of 3 at 0.700"). Mirrors the
        # official Space preset construction (main first, polish last).
        polish = max(1, round(steps * 0.3))
        schedule = tuple([float(guidance)] * (steps - polish) + [3.0] * polish)
        images: list[Image.Image] = []
        with self._lock:
            for s in seeds:
                gen = torch.Generator(device="cuda:0").manual_seed(int(s))
                base = dict(
                    prompt=prompt, width=width, height=height,
                    num_inference_steps=steps, generator=gen,
                )
                try:
                    out = self._pipe(**base, guidance_schedule=schedule)
                except TypeError:
                    # pipeline doesn't accept guidance_schedule -> constant CFG
                    out = self._pipe(**base, guidance_scale=guidance)
                images.append(out.images[0])
        return images

    # ---- public: circle-to-modify (inpainting) --------------------------- #
    def inpaint(
        self,
        image_b64: str,
        mask_b64: str,
        prompt: str,
        steps: Optional[int] = None,
        guidance: Optional[float] = None,
        seed: Optional[int] = None,
    ) -> GenResult:
        self.load()
        base = _b64_to_pil(image_b64)
        mask = _b64_to_pil(mask_b64).convert("L").resize(base.size)
        steps = int(steps or settings.default_steps)
        guidance = float(guidance if guidance is not None else settings.default_guidance)
        s = int(seed) if seed is not None else random.randint(0, 2**31 - 1)

        t0 = time.time()
        if self.mock:
            result = self._mock_inpaint(base, mask, prompt, s)
        else:
            result = self._inpaint_real(base, mask, prompt, steps, guidance, s)

        return GenResult(
            images_b64=[_pil_to_b64(result)],
            seeds=[s],
            elapsed=round(time.time() - t0, 2),
            mock=self.mock,
            width=base.width,
            height=base.height,
        )

    def _inpaint_real(self, base, mask, prompt, steps, guidance, seed):
        torch = self._torch
        prompt = self._expand_prompt(prompt)
        with self._lock:
            # Lazily build an inpaint pipeline that shares the loaded weights,
            # so we don't load the 9GB model twice.
            if self._inpaint_pipe is None:
                try:
                    from diffusers import AutoPipelineForInpainting

                    self._inpaint_pipe = AutoPipelineForInpainting.from_pipe(self._pipe)
                except Exception as exc:  # noqa: BLE001
                    print(f"[engine] dedicated inpaint pipe unavailable ({exc}); "
                          "using img2img-style region regen")
                    self._inpaint_pipe = self._pipe

            gen = torch.Generator(device="cuda:0").manual_seed(int(seed))
            try:
                out = self._inpaint_pipe(
                    prompt=prompt,
                    image=base,
                    mask_image=mask,
                    width=base.width,
                    height=base.height,
                    num_inference_steps=steps,
                    guidance_scale=guidance,
                    generator=gen,
                )
                result = out.images[0]
            except TypeError:
                # Pipeline has no mask support: regenerate full image and
                # composite only the masked region back in (soft edges).
                full = self._pipe(
                    prompt=prompt, width=base.width, height=base.height,
                    num_inference_steps=steps, guidance_scale=guidance, generator=gen,
                ).images[0]
                soft = mask.filter(ImageFilter.GaussianBlur(8))
                result = Image.composite(full, base, soft)
        return result

    # ---- public: image-to-image (transform your own photo) --------------- #
    def img2img(
        self,
        image_b64: str,
        prompt: str,
        strength: float = 0.6,
        n: int = 1,
        steps: Optional[int] = None,
        guidance: Optional[float] = None,
        seed: Optional[int] = None,
    ) -> GenResult:
        """Reimagine an uploaded photo guided by a prompt.

        `strength` 0..1 = how much to change (0 keeps the photo, 1 ignores it).
        """
        self.load()
        base = _b64_to_pil(image_b64)
        base = self._fit_for_model(base)
        n = max(1, min(int(n), settings.max_batch))
        strength = max(0.05, min(float(strength), 0.95))
        steps = int(steps or settings.default_steps)
        guidance = float(guidance if guidance is not None else settings.default_guidance)
        base_seed = int(seed) if seed is not None else random.randint(0, 2**31 - 1)
        seeds = [base_seed + i for i in range(n)]

        t0 = time.time()
        if self.mock:
            images = [self._mock_img2img(base, prompt, strength, s) for s in seeds]
        else:
            images = self._img2img_real(base, prompt, strength, seeds, steps, guidance)

        return GenResult(
            images_b64=[_pil_to_b64(im) for im in images],
            seeds=seeds,
            elapsed=round(time.time() - t0, 2),
            mock=self.mock,
            width=base.width,
            height=base.height,
        )

    def _img2img_real(self, base, prompt, strength, seeds, steps, guidance):
        torch = self._torch
        prompt = self._expand_prompt(prompt)
        images: list[Image.Image] = []
        with self._lock:
            if self._img2img_pipe is None:
                try:
                    from diffusers import AutoPipelineForImage2Image

                    self._img2img_pipe = AutoPipelineForImage2Image.from_pipe(self._pipe)
                except Exception as exc:  # noqa: BLE001
                    print(f"[engine] dedicated img2img pipe unavailable ({exc}); reusing base")
                    self._img2img_pipe = self._pipe
            for s in seeds:
                gen = torch.Generator(device="cuda:0").manual_seed(int(s))
                out = self._img2img_pipe(
                    prompt=prompt,
                    image=base,
                    strength=strength,
                    num_inference_steps=steps,
                    guidance_scale=guidance,
                    generator=gen,
                )
                images.append(out.images[0])
        return images

    # ---- public: upscale / enhance (bigger & sharper) -------------------- #
    def upscale(self, image_b64: str, scale: int = 2) -> GenResult:
        """Enlarge an image and sharpen it.

        Uses a high-quality Lanczos resample + unsharp masking. This works on
        any hardware and never risks the T4 VRAM budget. If a latent upscaler
        is available it could be swapped in here, but Lanczos+sharpen gives a
        reliable, visibly larger and crisper result for everyday use.
        """
        self.load()
        img = _b64_to_pil(image_b64)
        scale = 2 if int(scale) not in (2, 4) else int(scale)
        target = (
            min(img.width * scale, settings.max_side * 2),
            min(img.height * scale, settings.max_side * 2),
        )
        t0 = time.time()
        big = img.resize(target, Image.LANCZOS)
        big = big.filter(ImageFilter.UnsharpMask(radius=2, percent=120, threshold=2))
        return GenResult(
            images_b64=[_pil_to_b64(big)],
            seeds=[0],
            elapsed=round(time.time() - t0, 2),
            mock=self.mock,
            width=big.width,
            height=big.height,
        )

    # ---- helpers ---------------------------------------------------------- #
    def _fit_for_model(self, img: Image.Image) -> Image.Image:
        """Downscale huge uploads and snap sides to multiples of 16."""
        max_in = min(settings.max_side, 1024)
        if max(img.size) > max_in:
            ratio = max_in / max(img.size)
            img = img.resize((int(img.width * ratio), int(img.height * ratio)), Image.LANCZOS)
        w = self._clamp_side(img.width)
        h = self._clamp_side(img.height)
        return img.resize((w, h), Image.LANCZOS)

    def _clamp_side(self, v: int) -> int:
        v = max(256, min(int(v), settings.max_side))
        return (v // 16) * 16  # Ideogram needs multiples of 16

    # ================== MOCK GENERATOR (no GPU needed) ==================== #
    def _seeded_palette(self, key: str):
        h = hashlib.sha256(key.encode("utf-8")).digest()
        def col(i):
            return (h[i] // 2 + 60, h[i + 1] // 2 + 60, h[i + 2] // 2 + 60)
        return col(0), col(3), col(6)

    def _mock_image(self, prompt: str, w: int, h: int, seed: int) -> Image.Image:
        """A pleasant deterministic gradient + blobs + label placeholder."""
        rng = random.Random(seed)
        c1, c2, c3 = self._seeded_palette(f"{prompt}-{seed}")
        img = Image.new("RGB", (w, h), c1)
        draw = ImageDraw.Draw(img, "RGBA")

        # Diagonal gradient
        for y in range(h):
            t = y / max(1, h - 1)
            r = int(c1[0] * (1 - t) + c2[0] * t)
            g = int(c1[1] * (1 - t) + c2[1] * t)
            b = int(c1[2] * (1 - t) + c2[2] * t)
            draw.line([(0, y), (w, y)], fill=(r, g, b))

        # Soft floating blobs
        for _ in range(7):
            rad = rng.randint(int(w * 0.08), int(w * 0.28))
            cx, cy = rng.randint(0, w), rng.randint(0, h)
            alpha = rng.randint(40, 120)
            draw.ellipse(
                [cx - rad, cy - rad, cx + rad, cy + rad],
                fill=(c3[0], c3[1], c3[2], alpha),
            )
        img = img.filter(ImageFilter.GaussianBlur(radius=max(2, w // 220)))

        # Watermark-ish label so it's obvious this is a preview
        draw = ImageDraw.Draw(img)
        label = (prompt or "ескіз").strip()
        if len(label) > 42:
            label = label[:39] + "…"
        font = self._load_font(int(h * 0.045))
        small = self._load_font(int(h * 0.028))
        draw.rectangle([0, h - int(h * 0.16), w, h], fill=(0, 0, 0, 90))
        draw.text((int(w * 0.04), h - int(h * 0.135)), label, fill=(255, 255, 255), font=font)
        draw.text((int(w * 0.04), h - int(h * 0.06)),
                  f"МОКЕТ • демо без GPU • seed {seed}", fill=(230, 230, 230), font=small)
        return img

    def _mock_inpaint(self, base: Image.Image, mask: Image.Image, prompt: str, seed: int):
        patch = self._mock_image(prompt or "зміна", base.width, base.height, seed)
        soft = mask.filter(ImageFilter.GaussianBlur(10))
        out = Image.composite(patch, base, soft)
        # outline the edited region so the effect is visible in demo mode
        edge = mask.filter(ImageFilter.FIND_EDGES).filter(ImageFilter.MaxFilter(5))
        out.paste((255, 255, 255), (0, 0), edge.point(lambda p: 180 if p > 30 else 0))
        return out

    def _mock_img2img(self, base: Image.Image, prompt: str, strength: float, seed: int):
        """Blend a generated overlay with the uploaded photo by `strength`."""
        overlay = self._mock_image(prompt or "переробка", base.width, base.height, seed)
        out = Image.blend(base, overlay, max(0.05, min(strength, 0.95)))
        draw = ImageDraw.Draw(out)
        small = self._load_font(int(base.height * 0.028))
        draw.text((int(base.width * 0.04), int(base.height * 0.03)),
                  "З ТВОГО ФОТО • демо", fill=(255, 255, 255), font=small)
        return out

    @staticmethod
    def _load_font(size: int):
        for path in (
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        ):
            try:
                return ImageFont.truetype(path, size)
            except Exception:  # noqa: BLE001
                continue
        return ImageFont.load_default()


# Singleton used by the server
engine = IdeogramEngine()
