"""
Конфігурація сервера «Мамина Студія».

Centralised configuration for the Ideogram 4 image generation server.
All values can be overridden with environment variables, so the same code
runs unchanged on Kaggle (real 2x T4 GPUs) and in a local/sandbox MOCK mode.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env_bool(name: str, default: bool) -> bool:
    val = os.environ.get(name)
    if val is None:
        return default
    return val.strip().lower() in {"1", "true", "yes", "on", "y"}


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


@dataclass
class Settings:
    # --- Server ---
    host: str = os.environ.get("HOST", "0.0.0.0")
    port: int = _env_int("PORT", 7860)

    # --- Model / engine ---
    # Hugging Face repo that ships the diffusers-compatible nf4 build.
    # nf4 is the ONLY quantization that runs on T4 (Turing) GPUs.
    model_repo: str = os.environ.get("IDEOGRAM_MODEL_REPO", "ideogram-ai/ideogram-4-nf4")

    # Force mock mode regardless of GPU availability (useful for UI work).
    force_mock: bool = _env_bool("MOCK_MODE", False)

    # When True the engine tries to spread the model across two GPUs:
    # text encoder (Qwen3-VL-8B) -> cuda:1, DiT transformer + VAE -> cuda:0.
    dual_gpu: bool = _env_bool("DUAL_GPU", True)

    # Run the conditional + unconditional transformer passes concurrently on the
    # two GPUs (~1.7x faster denoising on top of the fp16 fix). v2: the engine
    # CPU-offloads the idle text encoder during denoising, freeing the VRAM that
    # previously made this OOM at 1024^2 — verified safe at full 1024^2 (cuda:0
    # ~10GB/5.5 free, cuda:1 ~8.4GB/7.2 free). Falls back to sequential if the
    # parallel pipeline can't be built. Disable with CFG_PARALLEL=0.
    cfg_parallel: bool = _env_bool("CFG_PARALLEL", True)

    # Hugging Face token (weights are gated). Read lazily by the engine.
    hf_token: str | None = os.environ.get("HF_TOKEN") or os.environ.get("HUGGING_FACE_HUB_TOKEN")

    # Optional Ideogram "magic prompt" key. If set, plain prompts are expanded
    # into the rich JSON captions the model was trained on (better results).
    magic_prompt_key: str | None = os.environ.get("IDEOGRAM_API_KEY")

    # --- Generation defaults (tuned for T4 speed/quality balance) ---
    default_width: int = _env_int("DEFAULT_WIDTH", 1024)
    default_height: int = _env_int("DEFAULT_HEIGHT", 1024)
    # Bumped 20 -> 24: the fp16 + parallel-CFG speedups (~8.7x faster/step) make
    # more sampling steps affordable, so the default now yields visibly sharper,
    # more detailed images while a single image still renders ~7x faster than
    # the old 20-step default did. The UI quality slider can raise it further.
    default_steps: int = _env_int("DEFAULT_STEPS", 24)
    default_guidance: float = float(os.environ.get("DEFAULT_GUIDANCE", "6.0"))
    # v2: cap the text token budget. The pipeline pads every prompt to this many
    # tokens and the conditional transformer processes them all each step; real
    # prompts are tiny, so a smaller cap is faster with no quality change. Raise
    # via MAX_SEQ_LEN if you ever feed very long prompts.
    # v3: step caching. Recompute the transformer passes only every Nth step in
    # the middle of the denoising schedule and reuse the cached velocity on the
    # others (~Nx faster). 1 = off, 2 ≈ 1.8x (safe), 3 ≈ 2.2x (aggressive/"max"),
    # 4+ = fastest but visible quality drift. First/last 2 steps always run.
    cache_interval: int = _env_int("CACHE_INTERVAL", 1)
    max_seq_len: int = _env_int("MAX_SEQ_LEN", 512)
    # Hard cap so a single request can never exhaust VRAM / hang the GPUs.
    max_batch: int = _env_int("MAX_BATCH", 4)
    max_side: int = _env_int("MAX_SIDE", 2048)

    # --- Paths ---
    base_dir: Path = field(default_factory=lambda: Path(__file__).resolve().parent.parent)

    @property
    def frontend_dir(self) -> Path:
        return self.base_dir / "frontend"

    @property
    def outputs_dir(self) -> Path:
        d = self.base_dir / "outputs"
        d.mkdir(parents=True, exist_ok=True)
        return d


settings = Settings()
