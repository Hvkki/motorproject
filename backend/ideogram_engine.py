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
# v2: let the CPU-side work (tokenisation, scheduler math, image post-proc, the
# bnb dequant launch overhead) use every core instead of one.
_cpus = str(os.cpu_count() or 4)
os.environ.setdefault("OMP_NUM_THREADS", _cpus)
os.environ.setdefault("MKL_NUM_THREADS", _cpus)

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
        try:
            torch.set_num_threads(os.cpu_count() or 4)
        except Exception:  # noqa: BLE001
            pass
        print("[engine] ===== Мамина Студія engine v3.3 (no false-positive prompt block) =====")
        self._preload_cuda_libs()
        n_gpus = torch.cuda.device_count()
        names = [torch.cuda.get_device_name(i) for i in range(n_gpus)]
        print(f"[engine] CUDA GPUs detected: {n_gpus} -> {names}")
        self._apply_bnb_shim()
        PipeCls = self._pipeline_class()

        common = dict(torch_dtype=torch.float16)  # T4 = fp16
        if settings.hf_token:
            common["token"] = settings.hf_token

        pipe = self._load_split(common, n_gpus, names)
        if pipe is None:
            pipe = self._load_balanced(PipeCls, common, n_gpus, names)
        if pipe is None:
            pipe = self._load_offload(PipeCls, common, names)

        # v3.4: THE critical T4 speed fix — force nf4 compute dtype to fp16.
        # Measured 4.5x speedup (75.0s -> 16.7s per step). See _force_fp16_compute.
        self._force_fp16_compute(pipe)

        for attr in ("enable_attention_slicing", "enable_vae_slicing", "enable_vae_tiling"):
            fn = getattr(pipe, attr, None)
            if callable(fn):
                try:
                    fn()
                except Exception:  # noqa: BLE001
                    pass

        self._guard_meta_quant_state(pipe)

        # v2.2: force memory-efficient attention. The Ideogram4 attention passes
        # a block-diagonal mask; on a T4 the math/flash paths materialise the
        # full NxN score matrix (~9GB at 1024^2) -> OOM even with weights split.
        # The mem-efficient SDPA kernel computes attention in tiles without that
        # matrix, slashing activation memory AND speeding attention up.
        try:
            torch.backends.cuda.enable_flash_sdp(False)        # not on Turing
            torch.backends.cuda.enable_math_sdp(False)         # the NxN hog
            torch.backends.cuda.enable_mem_efficient_sdp(True) # low-memory path
            print("[engine] forced memory-efficient SDPA attention")
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] sdpa backend set skipped: {exc}")
        for _n, _m in (pipe.components.items() if hasattr(pipe, "components") else []):
            fn = getattr(_m, "set_attention_backend", None)
            if callable(fn):
                try:
                    fn("native")  # route dispatch_attention_fn -> torch SDPA
                except Exception:  # noqa: BLE001
                    pass

        self._pipe = pipe
        self.mock = False
        print(f"[engine] ready: {self.device_info}")

    def _force_fp16_compute(self, pipe) -> None:
        """CRITICAL Turing/T4 speed fix (≈4.5x faster per step, measured).

        The ideogram-4-nf4 checkpoint was quantized with
        `bnb_4bit_compute_dtype=bfloat16`. Turing GPUs (T4, sm_75) have fp16
        tensor cores but NO bf16 tensor cores, so every 4-bit matmul
        dequantizes to bf16 and runs through the slow MAGMA `sgemmEx` CUDA-core
        fallback — profiling showed `magma_sgemmEx_kernel<float,__nv_bfloat16>`
        was ~90% of the per-step CUDA time (≈32s of 36s per forward).

        Forcing every `bnb.nn.Linear4bit` to fp16 compute (both `compute_dtype`
        and the per-weight `quant_state.dtype`) routes the matmul to the
        cuBLAS fp16 tensor-core path instead. Measured: 75.0s -> 16.7s per step
        at 1024x1024. fp16 is the native half precision for this GPU, so output
        quality is preserved. No-op on Ampere+ (which has bf16 tensor cores) is
        harmless: fp16 there is equally fast.
        """
        torch = self._torch
        try:
            import bitsandbytes as bnb
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] fp16-compute fix skipped (no bitsandbytes): {exc}")
            return
        n = 0
        comps = pipe.components.items() if hasattr(pipe, "components") else []
        for _name, module in comps:
            if not hasattr(module, "modules"):
                continue
            for m in module.modules():
                if isinstance(m, bnb.nn.Linear4bit):
                    try:
                        m.compute_dtype = torch.float16
                        qs = getattr(getattr(m, "weight", None), "quant_state", None)
                        if qs is not None and getattr(qs, "dtype", None) is not None:
                            qs.dtype = torch.float16
                        n += 1
                    except Exception:  # noqa: BLE001
                        pass
        print(f"[engine] T4 fp16-compute fix: patched {n} Linear4bit modules "
              f"(bf16->fp16 matmul, ~4.5x faster per step)")

    def _guard_meta_quant_state(self, pipe) -> None:
        """Heal bitsandbytes QuantState objects whose `code` (the fixed nf4
        codebook) was left on the meta device by accelerate's multi-GPU
        dispatch. Without this, the first forward pass crashes with
        `NotImplementedError: Cannot copy out of meta tensor; no data!` when a
        hook calls QuantState.to(device). The codebook is a constant, so we
        rebuild any meta `code` from a healthy one (or bnb's nf4 map).
        """
        torch = self._torch
        try:
            from bitsandbytes.functional import QuantState
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] meta QuantState guard skipped (no bnb): {exc}")
            return

        # 1) Find a reference (non-meta) nf4 codebook from the loaded weights.
        ref_code = None
        try:
            for _, module in pipe.components.items() if hasattr(pipe, "components") else []:
                if not hasattr(module, "modules"):
                    continue
                for m in module.modules():
                    qs = getattr(getattr(m, "weight", None), "quant_state", None)
                    code = getattr(qs, "code", None)
                    if isinstance(code, torch.Tensor) and not code.is_meta:
                        ref_code = code.detach().to("cpu")
                        break
                if ref_code is not None:
                    break
        except Exception:  # noqa: BLE001
            pass

        # 2) Fallback: regenerate the canonical nf4 codebook.
        if ref_code is None:
            try:
                from bitsandbytes.functional import create_normal_map
                ref_code = create_normal_map().detach().to("cpu")
            except Exception:  # noqa: BLE001
                ref_code = torch.tensor(
                    [-1.0, -0.6961928, -0.5250731, -0.3949175, -0.2844414,
                     -0.1847734, -0.0910500, 0.0, 0.0795803, 0.1609302,
                     0.2461123, 0.3379152, 0.4407098, 0.5626170, 0.7229568, 1.0],
                    dtype=torch.float32,
                )

        if getattr(QuantState, "_meta_guarded", False):
            return
        _orig_to = QuantState.to

        def _safe_to(self, device, _orig_to=_orig_to, _ref=ref_code):
            code = getattr(self, "code", None)
            if isinstance(code, torch.Tensor) and code.is_meta and _ref is not None:
                self.code = _ref.to(device=device, dtype=code.dtype)
            ns = getattr(self, "state2", None)
            if ns is not None:
                nc = getattr(ns, "code", None)
                if isinstance(nc, torch.Tensor) and nc.is_meta and _ref is not None:
                    ns.code = _ref.to(device=device, dtype=nc.dtype)
            return _orig_to(self, device)

        QuantState.to = _safe_to
        QuantState._meta_guarded = True
        print("[engine] bitsandbytes meta-QuantState guard installed")

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

    def _load_split(self, common, n_gpus, names):
        """PRIMARY dual-GPU path: load every component WHOLE onto a single GPU.

        `device_map="balanced"` splits individual components across both T4s,
        which (a) made accelerate offload nf4 layers to the meta device and
        (b) stranded the text encoder's rotary `inv_freq` buffer on a different
        GPU than its `position_ids` (RuntimeError: tensors on cuda:0 vs cuda:1).
        Pinning each component whole avoids both.

        Layout (sizes: enc 5.1G, transformer 4.9G, uncond 4.9G, vae 0.16G):
          * cuda:0 -> text_encoder + unconditional_transformer + vae. cuda:0 is
            the `_execution_device` (first model component), so the encoder's
            direct submodule calls (rotary/embed, built on that device) stay
            consistent, and the unconditional pass runs here.
          * cuda:1 -> transformer (conditional). Called via forward(), with an
            io_same_device hook so its inputs hop to cuda:1 and the output hops
            back to cuda:0 for the blend.

        The two transformers are deliberately on DIFFERENT GPUs so the parallel
        denoising loop (see _parallel_pipeline_class) can run the conditional
        and unconditional forwards concurrently, ~halving per-step time.
        """
        if not (settings.dual_gpu and n_gpus >= 2):
            return None
        torch = self._torch
        try:
            from diffusers import (
                AutoencoderKLFlux2,
                FlowMatchEulerDiscreteScheduler,
                Ideogram4Transformer2DModel,
            )
            from transformers import AutoModel, AutoTokenizer
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] split load unavailable ({exc}); will try balanced")
            return None
        try:
            repo = settings.model_repo
            tok = dict(token=settings.hf_token) if settings.hf_token else {}
            dt = dict(torch_dtype=torch.float16)
            parallel = bool(getattr(settings, "cfg_parallel", False))
            # Sequential (default): both transformers on cuda:1, encoder+vae on
            # cuda:0 -> fits 1024^2, reliable. Parallel: uncond moves to cuda:0
            # so the two passes run on separate GPUs concurrently (faster, but
            # encoder+uncond+acts is tight -> use <=768^2).
            uncond_gpu = 0 if parallel else 1
            print(f"[engine] loading {repo} per-component (parallel={parallel}): "
                  f"enc+vae@cuda:0, transformer@cuda:1, uncond@cuda:{uncond_gpu}…")

            text_encoder = AutoModel.from_pretrained(
                repo, subfolder="text_encoder", device_map={"": 0}, **dt, **tok
            )
            vae = AutoencoderKLFlux2.from_pretrained(
                repo, subfolder="vae", device_map={"": 0}, **dt, **tok
            )
            transformer = Ideogram4Transformer2DModel.from_pretrained(
                repo, subfolder="transformer", device_map={"": 1}, **dt, **tok
            )
            uncond = Ideogram4Transformer2DModel.from_pretrained(
                repo, subfolder="unconditional_transformer", device_map={"": uncond_gpu}, **dt, **tok
            )
            scheduler = FlowMatchEulerDiscreteScheduler.from_pretrained(
                repo, subfolder="scheduler", **tok
            )
            tokenizer = AutoTokenizer.from_pretrained(repo, subfolder="tokenizer", **tok)

            PipeCls = (self._parallel_pipeline_class() if parallel else None) or self._pipeline_class()
            pipe = PipeCls(
                scheduler=scheduler,
                vae=vae,
                text_encoder=text_encoder,
                tokenizer=tokenizer,
                transformer=transformer,
                unconditional_transformer=uncond,
                prompt_enhancer=None,
            )

            # Components on cuda:1 need an io_same_device align hook so their
            # inputs hop to cuda:1 and outputs hop back to cuda:0 (execution
            # device) for the blend. The conditional transformer is always on
            # cuda:1; in sequential mode the uncond is too.
            try:
                from accelerate.hooks import AlignDevicesHook, add_hook_to_module

                to_hook = [transformer] + ([] if parallel else [uncond])
                for m in to_hook:
                    add_hook_to_module(
                        m,
                        AlignDevicesHook(
                            execution_device=torch.device("cuda:1"),
                            io_same_device=True,
                        ),
                        append=False,
                    )
                print(f"[engine] align hook(s) attached to {len(to_hook)} module(s) on cuda:1")
            except Exception as exc:  # noqa: BLE001
                print(f"[engine] align hook attach failed: {exc}")

            if parallel:
                # v2: the text encoder is only used once (to build conditioning),
                # then sits idle for the whole denoising loop. Offloading it to
                # CPU RAM after encoding frees ~5GB on cuda:0 — which is exactly
                # what lets the unconditional pass run at 1024^2 there alongside
                # the vae. Strip its accelerate hooks first so plain
                # .to(cpu)/.to(cuda) moves are clean (it lives on the execution
                # device, so it needs no hook). Done per-generation in __call__.
                try:
                    from accelerate.hooks import remove_hook_from_module
                    remove_hook_from_module(text_encoder, recurse=True)
                    pipe._encoder_gpu = "cuda:0"
                    pipe._offload_encoder = True
                    print("[engine] encoder hooks stripped; CPU-offload during denoise enabled (1024^2 parallel)")
                except Exception as exc:  # noqa: BLE001
                    print(f"[engine] encoder offload setup skipped: {exc}")
                # v3: step-caching interval (1 = off). Read from settings.
                try:
                    pipe._cache_interval = max(1, int(getattr(settings, "cache_interval", 1)))
                    if pipe._cache_interval > 1:
                        print(f"[engine] step caching ON: recompute every {pipe._cache_interval} steps (mid-schedule)")
                except Exception:  # noqa: BLE001
                    pipe._cache_interval = 1

            self._assert_no_meta(pipe)
            self.device_info = {"mode": "cuda-split", "gpus": names,
                                "parallel": parallel,
                                "layout": f"enc+vae@0, transformer@1, uncond@{uncond_gpu}"}
            return pipe
        except Exception as exc:  # noqa: BLE001
            import traceback
            traceback.print_exc()
            print(f"[engine] per-component split load failed ({exc}); will try balanced")
            return None

    def _parallel_pipeline_class(self):
        """Subclass of Ideogram4Pipeline whose denoising loop runs the
        conditional (cuda:1) and unconditional (cuda:0) transformer passes
        CONCURRENTLY in two threads. Because the two transformers sit on
        different GPUs with independent CUDA streams, the heavy forwards overlap
        and per-step time roughly halves. Everything else (encode, schedule,
        VAE decode) is copied verbatim from the stock __call__. Returns None on
        any import/shape surprise so the caller falls back to the stock class.
        """
        torch = self._torch
        try:
            import threading as _th

            from diffusers import Ideogram4Pipeline
            from diffusers.pipelines.ideogram4 import pipeline_ideogram4 as _p
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] parallel pipeline unavailable ({exc}); using stock loop")
            return None

        class _ParallelIdeogram4(Ideogram4Pipeline):
            @torch.no_grad()
            def __call__(
                self, prompt=None, height=2048, width=2048, num_inference_steps=48,
                guidance_scale=None, guidance_schedule=(7.0,) * 45 + (3.0,) * 3,
                mu=0.0, std=1.5, prompt_upsampling=False,
                prompt_upsampling_temperature=_p.PROMPT_UPSAMPLE_TEMPERATURE,
                max_sequence_length=2048, num_images_per_prompt=1, generator=None,
                latents=None, output_type="pil", return_dict=True,
                callback_on_step_end=None, callback_on_step_end_tensor_inputs=["latents"],
            ):
                self.check_inputs(
                    prompt=prompt, height=height, width=width,
                    num_inference_steps=num_inference_steps, guidance_scale=guidance_scale,
                    guidance_schedule=guidance_schedule,
                    callback_on_step_end_tensor_inputs=callback_on_step_end_tensor_inputs,
                )
                if isinstance(prompt, str):
                    batch_size = 1
                elif isinstance(prompt, list):
                    batch_size = len(prompt)
                # v3.1: if the encoder is CPU-offloaded, bring it back to its GPU
                # BEFORE reading _execution_device. A hookless encoder stranded on
                # CPU (from the previous image's offload) makes _execution_device
                # report CPU on the 2nd+ image -> token_ids land on CPU -> the
                # embedding lookup crashes ('index is on cpu, ... cuda:0').
                _offload_enc = getattr(self, "_offload_encoder", False)
                _enc_gpu = getattr(self, "_encoder_gpu", "cuda:0")
                if _offload_enc:
                    try:
                        self.text_encoder.to(_enc_gpu)
                    except Exception:  # noqa: BLE001
                        pass
                device = torch.device(_enc_gpu) if _offload_enc else self._execution_device
                self._guidance_scale = guidance_scale
                self._interrupt = False
                if prompt_upsampling:
                    prompt = self.upsample_prompt(
                        prompt, height=height, width=width,
                        temperature=prompt_upsampling_temperature,
                        generator=generator, device=device,
                    )
                grid_h, grid_w = (
                    height // (self.vae_scale_factor * self.patch_size),
                    width // (self.vae_scale_factor * self.patch_size),
                )
                num_image_tokens = grid_h * grid_w
                llm_features, position_ids, segment_ids, indicator = self.encode_prompt(
                    prompt=prompt, grid_h=grid_h, grid_w=grid_w,
                    max_sequence_length=max_sequence_length, device=device,
                )
                # v3.1: park the (now idle) encoder on CPU to free ~5GB for the
                # 1024^2 denoising loop. (It was brought back to GPU at the top.)
                if _offload_enc:
                    try:
                        self.text_encoder.to("cpu")
                        import gc as _gc
                        _gc.collect()
                        torch.cuda.synchronize()
                        torch.cuda.empty_cache()
                    except Exception:  # noqa: BLE001
                        pass
                llm_features = _p._expand_tensor_to_effective_batch(llm_features, batch_size, num_images_per_prompt)
                position_ids = _p._expand_tensor_to_effective_batch(position_ids, batch_size, num_images_per_prompt)
                segment_ids = _p._expand_tensor_to_effective_batch(segment_ids, batch_size, num_images_per_prompt)
                indicator = _p._expand_tensor_to_effective_batch(indicator, batch_size, num_images_per_prompt)
                neg_llm_features = torch.zeros(
                    batch_size * num_images_per_prompt, num_image_tokens,
                    llm_features.shape[-1], dtype=llm_features.dtype, device=device,
                )
                neg_position_ids = position_ids[:, max_sequence_length:]
                neg_segment_ids = segment_ids[:, max_sequence_length:]
                neg_indicator = indicator[:, max_sequence_length:]
                schedule_mu = _p._resolution_aware_mu(height=height, width=width, base_mu=mu)
                sigmas = _p._logit_normal_sigmas(num_inference_steps, schedule_mu, std=std, device=device)
                self.scheduler.set_timesteps(sigmas=sigmas.tolist(), device=device)
                timesteps = self.scheduler.timesteps
                self._num_timesteps = len(timesteps)
                if guidance_scale is not None:
                    guidance_schedule = [float(guidance_scale)] * num_inference_steps
                gw = torch.as_tensor(guidance_schedule, dtype=torch.float32, device=device)
                latent_dim = self.transformer.config.in_channels
                latents = self.prepare_latents(
                    batch_size=batch_size * num_images_per_prompt, num_image_tokens=num_image_tokens,
                    latent_dim=latent_dim, dtype=torch.float32, device=device,
                    generator=generator, latents=latents,
                )
                max_text_tokens = max_sequence_length
                text_z_padding = torch.zeros(
                    batch_size * num_images_per_prompt, max_text_tokens, latent_dim,
                    dtype=torch.float32, device=device,
                )
                llm_features = llm_features.to(self.transformer.dtype)
                neg_llm_features = neg_llm_features.to(self.unconditional_transformer.dtype)
                num_train_timesteps = self.scheduler.config.num_train_timesteps
                # v3: step caching. The transformer passes are the expensive part;
                # consecutive flow-matching steps produce very similar velocities.
                # So recompute them only every `cache_interval` steps in the middle
                # of the schedule and REUSE the cached velocities in between
                # (always re-blended with the current step's guidance weight, so
                # the polish schedule is still honoured). The first/last `warm`
                # steps are always computed — they set structure and final detail.
                cache_int = max(1, int(getattr(self, "_cache_interval", 1)))
                warm = 2
                cached_pos = cached_neg = None
                with self.progress_bar(total=num_inference_steps) as progress_bar:
                    for i, t in enumerate(timesteps):
                        if self.interrupt:
                            continue
                        t_model = 1.0 - (t.float() / num_train_timesteps)
                        t_model = t_model.expand(batch_size * num_images_per_prompt).to(self.transformer.dtype)

                        is_full = (
                            cache_int <= 1 or cached_pos is None
                            or i < warm or i >= self._num_timesteps - warm
                            or ((i - warm) % cache_int == 0)
                        )
                        if is_full:
                            pos_z = torch.cat([text_z_padding, latents], dim=1).to(self.transformer.dtype)
                            out, err = {}, {}

                            def _cond():
                                try:
                                    with torch.no_grad():
                                        o = self.transformer(
                                            hidden_states=pos_z, timestep=t_model,
                                            encoder_hidden_states=llm_features, position_ids=position_ids,
                                            segment_ids=segment_ids, indicator=indicator, return_dict=False,
                                        )[0]
                                        out["pos"] = o[:, max_text_tokens:].to(torch.float32)
                                except Exception as e:  # noqa: BLE001
                                    err["pos"] = e

                            def _uncond():
                                try:
                                    with torch.no_grad():
                                        o = self.unconditional_transformer(
                                            hidden_states=latents.to(self.unconditional_transformer.dtype),
                                            timestep=t_model, encoder_hidden_states=neg_llm_features,
                                            position_ids=neg_position_ids, segment_ids=neg_segment_ids,
                                            indicator=neg_indicator, return_dict=False,
                                        )[0]
                                        out["neg"] = o.to(torch.float32)
                                except Exception as e:  # noqa: BLE001
                                    err["neg"] = e

                            ta = _th.Thread(target=_cond)
                            tb = _th.Thread(target=_uncond)
                            ta.start(); tb.start(); ta.join(); tb.join()
                            if err:
                                raise next(iter(err.values()))
                            cached_pos, cached_neg = out["pos"], out["neg"]

                        pos_v, neg_v = cached_pos, cached_neg
                        self._guidance_scale = guidance_schedule[i]
                        gw_i = gw[i]
                        v = gw_i * pos_v + (1.0 - gw_i) * neg_v
                        latents = self.scheduler.step(-v, t, latents, return_dict=False)[0]
                        if callback_on_step_end is not None:
                            callback_kwargs = {k: locals()[k] for k in callback_on_step_end_tensor_inputs}
                            callback_outputs = callback_on_step_end(self, i, t, callback_kwargs)
                            latents = callback_outputs.pop("latents", latents)
                        progress_bar.update()

                if output_type == "latent":
                    image = latents
                else:
                    z = latents
                    bn_mean = self.vae.bn.running_mean.view(1, 1, -1).to(device=z.device, dtype=z.dtype)
                    bn_std = torch.sqrt(self.vae.bn.running_var + self.vae.config.batch_norm_eps).view(1, 1, -1)
                    bn_std = bn_std.to(device=z.device, dtype=z.dtype)
                    z = z * bn_std + bn_mean
                    patch = self.patch_size
                    ae_channels = z.shape[-1] // (patch * patch)
                    z = z.view(batch_size * num_images_per_prompt, grid_h, grid_w, patch, patch, ae_channels)
                    z = z.permute(0, 5, 1, 3, 2, 4).contiguous()
                    z = z.view(batch_size * num_images_per_prompt, ae_channels, grid_h * patch, grid_w * patch)
                    decoded = self.vae.decode(z.to(self.vae.dtype), return_dict=False)[0]
                    image = self.image_processor.postprocess(decoded.float(), output_type=output_type)

                self.maybe_free_model_hooks()
                if not return_dict:
                    return (image,)
                return _p.Ideogram4PipelineOutput(images=image)

        return _ParallelIdeogram4

    def _load_balanced(self, PipeCls, common, n_gpus, names):
        """Shard the quantized (nf4) model across BOTH T4s via device_map.

        accelerate places every component (both DiT branches, the Qwen3-VL text
        encoder, VAE) across the GPUs and adds cross-device transfer hooks. We
        KEEP nf4 (never .dequantize()) so it fits in 2x16GB.
        """
        if not (settings.dual_gpu and n_gpus >= 2):
            return None
        try:
            # The whole nf4 model is only ~15GB (transformer 4.9 + uncond 4.9 +
            # text_encoder 5.1 + vae 0.2) and 2x T4 give ~31GB — it fits easily.
            # BUT accelerate over-estimates bitsandbytes 4bit module sizes (it
            # sizes them closer to fp16), so with a tight budget it wrongly
            # decides the model doesn't fit and OFFLOADS the overflow to the
            # `meta` device. Those offloaded 4bit params then crash at inference:
            #   NotImplementedError: Cannot copy out of meta tensor; no data!
            # (absmax is real data that was never materialised). There is also no
            # "cpu" budget here — CPU offload hits the same bnb-4bit failure.
            # Fix: hand accelerate a deliberately generous per-GPU budget so it
            # never offloads. Actual on-GPU usage (~7.5GB/GPU) stays well under
            # the physical 15.6GB.
            max_memory = {i: "40GiB" for i in range(n_gpus)}
            print(f"[engine] loading {settings.model_repo} nf4, device_map=balanced {max_memory} (generous budget, no offload)…")
            pipe = PipeCls.from_pretrained(
                settings.model_repo, device_map="balanced", max_memory=max_memory, **common
            )
            self._assert_no_meta(pipe)
            self.device_info = {"mode": "cuda-balanced", "gpus": names}
            return pipe
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] device_map=balanced failed ({exc}); will try CPU offload")
            return None

    def _assert_no_meta(self, pipe) -> None:
        """Loud fail-fast check: if accelerate still parked any weight on the
        meta device, inference WILL crash later with 'Cannot copy out of meta
        tensor'. Surface it now in the load log instead."""
        torch = self._torch
        meta = []
        try:
            comps = pipe.components.values() if hasattr(pipe, "components") else []
            for module in comps:
                if not hasattr(module, "named_parameters"):
                    continue
                for name, p in module.named_parameters():
                    if getattr(p, "is_meta", False):
                        meta.append(name)
                        if len(meta) >= 5:
                            break
        except Exception:  # noqa: BLE001
            return
        if meta:
            print(f"[engine] WARNING: {len(meta)}+ params still on META after load "
                  f"(e.g. {meta[:3]}). Inference will fail — budget/placement needs work.")
        else:
            print("[engine] meta-check OK: no parameters on the meta device.")

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
        via the hosted "magic prompt" API. That endpoint is Hive-moderated and
        can FALSE-POSITIVE on wholesome prompts (e.g. "Happy Birthday cake"),
        returning a block/refusal message. We must never let such a response
        replace the user's wording — on any moderation/error/odd shape we fall
        back to the literal prompt, which the local model renders fine (the
        diffusers pipeline carries no runtime Hive filter). Set IDEOGRAM_API_KEY
        only if you actually want the (moderated) expansion.
        """
        if not settings.magic_prompt_key:
            return prompt
        try:
            import json as _json

            import requests

            resp = requests.post(
                "https://api.ideogram.ai/v1/magic-prompt",
                headers={"Api-Key": settings.magic_prompt_key},
                json={"prompt": prompt},
                timeout=15,
            )
            if resp.ok:
                data = resp.json() if resp.content else {}
                # The API returns the structured caption under "json_prompt";
                # older shapes used "prompt".
                jp = data.get("json_prompt")
                cand = _json.dumps(jp, ensure_ascii=False) if isinstance(jp, dict) else data.get("prompt")
                blockish = ("block", "moderat", "policy", "violat", "not allowed",
                            "cannot", "unable", "security", "rejected", "flagged", "unsafe")
                if (isinstance(cand, str) and cand.strip()
                        and not any(w in cand.lower() for w in blockish)):
                    return cand
                print("[engine] magic-prompt returned no usable expansion (moderated?); using literal prompt")
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
        # v2: the pipeline pads every prompt to max_sequence_length (2048) and
        # the conditional transformer processes that whole text region each step.
        # Real prompts are tiny, so trimming the cap cuts the conditional
        # sequence length with ZERO quality change (the dropped tokens were
        # masked padding). ~20-30% faster on the conditional pass.
        max_seq = int(getattr(settings, "max_seq_len", 512))
        images: list[Image.Image] = []
        with self._lock:
            for s in seeds:
                gen = torch.Generator(device="cuda:0").manual_seed(int(s))
                base = dict(
                    prompt=prompt, width=width, height=height,
                    num_inference_steps=steps, generator=gen,
                    max_sequence_length=max_seq,
                )
                try:
                    out = self._pipe(**base, guidance_schedule=schedule)
                except TypeError:
                    # pipeline doesn't accept guidance_schedule -> constant CFG
                    base.pop("max_sequence_length", None)
                    try:
                        out = self._pipe(**base, guidance_schedule=schedule, max_sequence_length=max_seq)
                    except TypeError:
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
            images = self._img2img_sdedit(base, prompt, strength, seeds, steps, guidance)

        return GenResult(
            images_b64=[_pil_to_b64(im) for im in images],
            seeds=seeds,
            elapsed=round(time.time() - t0, 2),
            mock=self.mock,
            width=base.width,
            height=base.height,
        )

    def _img2img_sdedit(self, base, prompt, strength, seeds, steps, guidance):
        """Flow-matching img2img (SDEdit) that REUSES the pipeline's own __call__
        (which already fits 1024^2 on 2x T4 for text2img), so img2img inherits
        that proven memory profile instead of a hand-rolled denoise that OOMs the
        cramped cuda:0.

        Approach (the "find a way to push to 1024" fix):
          * VAE-encode the init image on **cuda:1** (which has free VRAM), adding
            ZERO pressure to cuda:0 (where the text encoder + uncond DiT live).
          * Pack + batch-norm-normalise into the model's packed latent layout.
          * Inject the strength-noised init latents by monkeypatching
            ``prepare_latents``; run only the schedule tail by truncating
            ``set_timesteps`` (Flux ``get_timesteps``/``scale_noise`` recipe).
          * Call the pipeline normally -> its tested encoder-offload + parallel
            dual-GPU denoise + VAE decode all run unchanged.
        """
        import types
        torch = self._torch
        prompt = self._expand_prompt(prompt)
        P = self._pipe
        from diffusers.pipelines.ideogram4 import pipeline_ideogram4 as _p
        try:
            from accelerate.hooks import remove_hook_from_module as _rm
        except Exception:  # noqa: BLE001
            _rm = None

        def _free():
            try:
                f0, _ = torch.cuda.mem_get_info(0)
                f1, _ = torch.cuda.mem_get_info(1)
                return f"cuda0={f0/1e9:.2f} cuda1={f1/1e9:.2f}GB"
            except Exception:  # noqa: BLE001
                return "?"

        patch = P.patch_size
        vsf = P.vae_scale_factor
        i2i_max = int(os.environ.get("IMG2IMG_MAX_SIDE", "1024"))
        if max(base.width, base.height) > i2i_max:
            r = i2i_max / max(base.width, base.height)
            base = base.resize((max(256, (int(base.width * r) // 16) * 16),
                                max(256, (int(base.height * r) // 16) * 16)), Image.LANCZOS)
        W, H = base.width, base.height
        grid_h, grid_w = H // (vsf * patch), W // (vsf * patch)
        num_image_tokens = grid_h * grid_w
        steps = max(2, int(steps))
        strength = max(0.05, min(float(strength), 0.95))

        images: list[Image.Image] = []
        with self._lock:
            print(f"[engine] img2img(sdedit) ENTER {W}x{H} {_free()}", flush=True)
            try:
                torch.cuda.empty_cache()
            except Exception:  # noqa: BLE001
                pass
            vae = P.vae
            home = next(vae.parameters()).device
            enc_dev = torch.device("cuda:1") if torch.cuda.device_count() > 1 else home
            # ---- VAE-encode the init image on the GPU with free VRAM (cuda:1) ----
            try:
                if _rm is not None:
                    try:
                        _rm(vae, recurse=True)
                    except Exception:  # noqa: BLE001
                        pass
                vae.to(enc_dev)
                _pt = getattr(vae, "use_tiling", False)
                try:
                    vae.use_tiling = False   # non-tiled: keeps the latent's exact grid size
                except Exception:  # noqa: BLE001
                    pass
                px = P.image_processor.preprocess(base, height=H, width=W).to(device=enc_dev, dtype=vae.dtype)
                with torch.no_grad():
                    z_sp = vae.encode(px).latent_dist.sample().detach()  # (1, ae, H/vsf, W/vsf)
                try:
                    vae.use_tiling = _pt
                except Exception:  # noqa: BLE001
                    pass
                ae = z_sp.shape[1]
                z = z_sp.float().view(1, ae, grid_h, patch, grid_w, patch)
                z = z.permute(0, 2, 4, 3, 5, 1).contiguous().view(1, num_image_tokens, ae * patch * patch)
                bn_mean = vae.bn.running_mean.view(1, 1, -1).to(enc_dev, torch.float32)
                bn_std = torch.sqrt(vae.bn.running_var + vae.config.batch_norm_eps).view(1, 1, -1).to(enc_dev, torch.float32)
                z0 = (z - bn_mean) / bn_std
                del px, z_sp, z
            finally:
                vae.to(home)                 # back to cuda:0 for the pipeline's decode
                try:
                    torch.cuda.empty_cache()
                except Exception:  # noqa: BLE001
                    pass
            z0 = z0.to("cuda:0")
            print(f"[engine] img2img(sdedit) encoded on {enc_dev} z0{tuple(z0.shape)} {_free()}", flush=True)

            # strength -> start sigma, using the SAME schedule the pipeline builds
            mu = _p._resolution_aware_mu(height=H, width=W, base_mu=0.0)
            sigmas = _p._logit_normal_sigmas(steps, mu, std=1.5, device="cuda:0")
            init_t = min(steps * strength, steps)
            t_start = int(max(steps - init_t, 0))
            t_start = max(0, min(t_start, steps - 1))
            sigma_start = float(sigmas[t_start])

            orig_prepare = P.prepare_latents
            orig_set = P.scheduler.set_timesteps

            def patched_prepare(self_p, batch_size, num_image_tokens, latent_dim, dtype, device, generator, latents=None):
                noise = torch.randn(z0.shape, generator=generator, device=z0.device, dtype=torch.float32)
                return (sigma_start * noise + (1.0 - sigma_start) * z0).to(device)

            def patched_set(*a, **k):
                orig_set(*a, **k)
                sch = P.scheduler
                sch.timesteps = sch.timesteps[t_start:]
                if getattr(sch, "sigmas", None) is not None:
                    sch.sigmas = sch.sigmas[t_start:]
                if hasattr(sch, "set_begin_index"):
                    sch.set_begin_index(0)
                sch._step_index = None

            P.prepare_latents = types.MethodType(patched_prepare, P)
            P.scheduler.set_timesteps = patched_set
            print(f"[engine] img2img(sdedit) strength={strength:.2f} start={t_start}/{steps} sigma={sigma_start:.3f}", flush=True)
            try:
                for s in seeds:
                    gen = torch.Generator(device="cuda:0").manual_seed(int(s))
                    out = P(prompt=prompt, height=H, width=W, num_inference_steps=steps,
                            guidance_scale=float(guidance), guidance_schedule=None,
                            num_images_per_prompt=1, generator=gen, output_type="pil")
                    images.append(out.images[0])
                    print(f"[engine] img2img(sdedit) seed={s} done {_free()}", flush=True)
            finally:
                P.prepare_latents = orig_prepare
                P.scheduler.set_timesteps = orig_set
        return images

    def _img2img_real(self, base, prompt, strength, seeds, steps, guidance):
        """Proper flow-matching image-to-image (SDEdit) for Ideogram-4.

        The diffusers Ideogram4 port ships NO img2img pipeline and the text2img
        ``__call__`` accepts no ``image``/``strength`` (only ``latents``). The
        previous code therefore fell back to the text2img pipe and raised a
        TypeError. We implement img2img directly, mirroring the canonical
        Flux/SD3 img2img recipe on the already-loaded components (no extra VRAM):

          1. VAE-encode the init image and pack it into the model's batch-norm-
             normalised packed latent layout (the exact inverse of the
             pipeline's decode step).
          2. Pick the start sigma from ``strength`` (Flux ``get_timesteps``):
             skip the first ``(1-strength)`` fraction of the schedule and noise
             the init latents to that sigma  ->  x = (1-sigma)*x0 + sigma*eps
             (flow-matching forward / ``scale_noise``).
          3. Run only the remaining "tail" denoising steps (same dual-transformer
             asymmetric-CFG loop as text2img) and decode.
        """
        torch = self._torch
        prompt = self._expand_prompt(prompt)
        P = self._pipe
        from diffusers.pipelines.ideogram4 import pipeline_ideogram4 as _p

        vae = P.vae
        device = next(vae.parameters()).device
        patch = P.patch_size
        vsf = P.vae_scale_factor

        def _free0():
            try:
                f0, _ = torch.cuda.mem_get_info(0)
                f1, _ = torch.cuda.mem_get_info(1)
                return f"cuda0_free={f0/1e9:.2f}GB cuda1_free={f1/1e9:.2f}GB"
            except Exception:  # noqa: BLE001
                return "mem?"
        print(f"[engine] img2img ENTER {_free0()}", flush=True)
        try:
            torch.cuda.empty_cache()   # reclaim VRAM leaked by any prior failed call
        except Exception:  # noqa: BLE001
            pass
        # VRAM safety: img2img adds a VAE *encode* pass (text2img only decodes),
        # which spikes cuda:0 (it also hosts the unconditional transformer). On a
        # 16GB T4 a full 1024^2 encode OOMs, so cap the long side for img2img.
        i2i_max = int(os.environ.get("IMG2IMG_MAX_SIDE", "384"))
        if max(base.width, base.height) > i2i_max:
            r = i2i_max / max(base.width, base.height)
            nw = max(256, (int(base.width * r) // 16) * 16)
            nh = max(256, (int(base.height * r) // 16) * 16)
            base = base.resize((nw, nh), Image.LANCZOS)
        W, H = base.width, base.height
        grid_h, grid_w = H // (vsf * patch), W // (vsf * patch)
        num_image_tokens = grid_h * grid_w
        max_seq = int(getattr(settings, "max_seq_len", 512))
        latent_dim = P.transformer.config.in_channels

        steps = max(2, int(steps))
        polish = max(1, round(steps * 0.3))
        schedule = [float(guidance)] * (steps - polish) + [3.0] * polish

        images: list[Image.Image] = []
        with self._lock:
            # ---- conditioning: bring the offloaded encoder back, encode, park it again ----
            offload = bool(getattr(P, "_offload_encoder", False))
            enc_gpu = getattr(P, "_encoder_gpu", "cuda:0")
            if offload:
                try:
                    P.text_encoder.to(enc_gpu)
                except Exception:  # noqa: BLE001
                    pass
            llm_features, position_ids, segment_ids, indicator = P.encode_prompt(
                prompt=prompt, grid_h=grid_h, grid_w=grid_w,
                max_sequence_length=max_seq, device=device,
            )
            if offload:
                try:
                    P.text_encoder.to("cpu")
                    import gc as _gc
                    _gc.collect()
                    torch.cuda.synchronize()
                    torch.cuda.empty_cache()
                except Exception:  # noqa: BLE001
                    pass
            print(f"[engine] img2img post-encode_prompt {_free0()}", flush=True)
            neg_llm_features = torch.zeros(
                1, num_image_tokens, llm_features.shape[-1],
                dtype=llm_features.dtype, device=device,
            )
            neg_position_ids = position_ids[:, max_seq:]
            neg_segment_ids = segment_ids[:, max_seq:]
            neg_indicator = indicator[:, max_seq:]
            llm_features = llm_features.to(P.transformer.dtype)
            neg_llm_features = neg_llm_features.to(P.unconditional_transformer.dtype)
            text_z_padding = torch.zeros(1, max_seq, latent_dim, dtype=torch.float32, device=device)

            # ---- VAE-encode init image -> packed, bn-normalised latents (inverse of decode) ----
            # Use a NON-tiled encode: tiling blends overlapping tiles and can change
            # the latent's spatial size (e.g. 80x80 instead of 64x64), desyncing it
            # from the model's token grid. uncond is parked, so a <=512 non-tiled
            # encode has ample headroom. Restore the tiling flag afterwards (text2img
            # decode at 1024 relies on it).
            _prev_tiling = getattr(vae, "use_tiling", False)
            try:
                vae.use_tiling = False
            except Exception:  # noqa: BLE001
                pass
            px = P.image_processor.preprocess(base, height=H, width=W).to(device=device, dtype=vae.dtype)
            z_sp = vae.encode(px).latent_dist.sample()                 # (1, ae, H/vsf, W/vsf)
            try:
                vae.use_tiling = _prev_tiling
            except Exception:  # noqa: BLE001
                pass
            ae = z_sp.shape[1]
            z = z_sp.float().view(1, ae, grid_h, patch, grid_w, patch)
            z = z.permute(0, 2, 4, 3, 5, 1).contiguous().view(1, num_image_tokens, ae * patch * patch)
            bn_mean = vae.bn.running_mean.view(1, 1, -1).to(device=device, dtype=torch.float32)
            bn_std = torch.sqrt(vae.bn.running_var + vae.config.batch_norm_eps)
            bn_std = bn_std.view(1, 1, -1).to(device=device, dtype=torch.float32)
            z0 = (z - bn_mean) / bn_std
            del px, z_sp, z
            try:
                torch.cuda.empty_cache()
            except Exception:  # noqa: BLE001
                pass
            print(f"[engine] img2img post-vae-encode {_free0()}", flush=True)

            # ---- schedule + strength-selected start sigma (Flux get_timesteps) ----
            schedule_mu = _p._resolution_aware_mu(height=H, width=W, base_mu=0.0)
            sigmas = _p._logit_normal_sigmas(steps, schedule_mu, std=1.5, device=device)
            P.scheduler.set_timesteps(sigmas=sigmas.tolist(), device=device)
            init_t = min(steps * float(strength), steps)
            t_start = int(max(steps - init_t, 0))
            t_start = max(0, min(t_start, steps - 1))
            sigma_start = float(sigmas[t_start])
            timesteps = P.scheduler.timesteps[t_start:]
            num_train = P.scheduler.config.num_train_timesteps
            print(f"[engine] img2img: strength={strength:.2f} -> {len(timesteps)}/{steps} steps "
                  f"(start sigma={sigma_start:.3f}), {grid_h*patch}x{grid_w*patch} latent")

            for s in seeds:
                gen = torch.Generator(device=device).manual_seed(int(s))
                noise = torch.randn(z0.shape, generator=gen, device=device, dtype=torch.float32)
                # flow-matching forward noising at the start sigma (SDEdit / scale_noise)
                latents = sigma_start * noise + (1.0 - sigma_start) * z0
                if hasattr(P.scheduler, "set_begin_index"):
                    P.scheduler.set_begin_index(t_start)
                P.scheduler._step_index = None
                for i, t in enumerate(timesteps):
                    t_model = (1.0 - (t.float() / num_train)).expand(1).to(P.transformer.dtype)
                    pos_z = torch.cat([text_z_padding, latents], dim=1).to(P.transformer.dtype)
                    pos_v = P.transformer(
                        hidden_states=pos_z, timestep=t_model, encoder_hidden_states=llm_features,
                        position_ids=position_ids, segment_ids=segment_ids, indicator=indicator,
                        return_dict=False,
                    )[0][:, max_seq:].to(torch.float32)
                    neg_v = P.unconditional_transformer(
                        hidden_states=latents.to(P.unconditional_transformer.dtype), timestep=t_model,
                        encoder_hidden_states=neg_llm_features, position_ids=neg_position_ids,
                        segment_ids=neg_segment_ids, indicator=neg_indicator, return_dict=False,
                    )[0].to(torch.float32)
                    gwt = float(schedule[min(t_start + i, len(schedule) - 1)])
                    v = gwt * pos_v + (1.0 - gwt) * neg_v
                    latents = P.scheduler.step(-v, t, latents, return_dict=False)[0]

                # ---- decode: bn-denorm + un-pack (mirror pipeline) -> VAE decode ----
                z = latents * bn_std + bn_mean
                z = z.view(1, grid_h, grid_w, patch, patch, ae).permute(0, 5, 1, 3, 2, 4).contiguous()
                z = z.view(1, ae, grid_h * patch, grid_w * patch)
                decoded = vae.decode(z.to(vae.dtype), return_dict=False)[0]
                images.append(P.image_processor.postprocess(decoded.float(), output_type="pil")[0])
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
