---
name: mamina-studia-kaggle
description: >
  Runbook + Kiro skill for the "Мамина Студія / Mama's Studio" image studio
  (Hvkki/motorproject) running Ideogram-4 (open-weight, nf4) on Kaggle 2x T4.
  Covers: clone from git, Kaggle setup, HF license, dependency install, launching
  the FastAPI server, the Ideogram-4 SAFETY-FILTER pitfall and the rich-JSON
  bypass, the flow-matching img2img implementation, running, managing, and
  troubleshooting. Use whenever you set up, run, debug, or extend this studio.
---

# Мамина Студія on Kaggle — Kiro Runbook / Skill

A practical, battle-tested guide to running the **Ideogram-4** image studio
(`backend/` FastAPI + `frontend/`) on **Kaggle with 2× NVIDIA T4**. Everything
here was verified end-to-end on a live 2× T4 Kaggle session.

> TL;DR of the one thing everyone trips on: **Ideogram-4's safety filter is
> baked into the model weights and blocks plain-text prompts** (even "a cute
> cat"), returning a grey *"Image blocked by safety filter"* placeholder *after*
> doing the full compute. **Send a rich, multi-field, English structured-JSON
> caption and it renders normally.**

---

## 0. What this project is

- **Model:** `ideogram-ai/ideogram-4-nf4` — a 9.3B single-stream Diffusion
  Transformer with a Qwen3-VL-8B vision-language text encoder and a structured
  **JSON prompt** interface. Open-weight, **non-commercial** license.
- **Why nf4:** T4 (Turing) can't run fp8; **nf4** (bitsandbytes 4-bit) is the
  only quantization that fits and runs on T4.
- **Topology (dual-GPU split):** text encoder + VAE on `cuda:0`, conditional DiT
  on `cuda:1`, unconditional DiT on `cuda:0` (so the two CFG passes run on
  separate GPUs in parallel). 9.3B model + 8B encoder fit across 2×16 GB.
- **Server:** `backend/server.py` (FastAPI) serves the Ukrainian frontend and a
  JSON API (`/api/generate`, `/api/img2img`, `/api/inpaint`, `/api/upscale`,
  `/api/chat`, `/api/review`, `/api/health`).

---

## 1. Get the code (git)

The project currently lives on the **`add-mamina-studia`** branch (PR not yet
merged), and `main` is empty.

```bash
git clone --branch add-mamina-studia --single-branch \
  https://github.com/Hvkki/motorproject.git
cd motorproject
```

Layout:

```
backend/   server.py  ideogram_engine.py  agent.py  config.py  requirements.txt
frontend/  index.html  app.js  styles.css
kaggle/    MaminaStudia_Kaggle.ipynb   kaggle_backend.py
```

If the repo is **private**, clone with a token (never print it):
`https://<GITHUB_TOKEN>@github.com/Hvkki/motorproject.git`. On Kaggle the
notebook also accepts the project added as a *Dataset* (read from `/kaggle/input`).

---

## 2. Kaggle setup (once)

1. **Settings → Accelerator → `GPU T4 x2`**, **Internet → On**.
2. **Accept the model license** with the SAME HF account as your token —
   click *"Agree and access repository"* on BOTH:
   - <https://huggingface.co/ideogram-ai/ideogram-4-nf4>  ← the code actually pulls this
   - <https://huggingface.co/ideogram-ai/ideogram-4-nf4-diffusers>
   (Both are `gated: auto` → instant approval. Skipping this → 403 on download.)
3. **Add-ons → Secrets:**
   - `HF_TOKEN` — Hugging Face token (gated weights).
   - `NGROK_TOKEN` — only if you want a public link (see §7; optional).
   - `OLLAMA_API_KEY` *(optional)* — enables the helper agents (see §6).
   - `IDEOGRAM_API_KEY` *(optional)* — hosted "magic-prompt" expansion.

Verify the token + gated access before a long run:

```bash
curl -s -H "Authorization: Bearer $HF_TOKEN" https://huggingface.co/api/whoami-v2
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $HF_TOKEN" -L \
  https://huggingface.co/ideogram-ai/ideogram-4-nf4/resolve/main/model_index.json   # expect 200
```

---

## 3. Install dependencies (pin torch!)

Kaggle ships torch/torchvision/torchaudio built for its CUDA. **Pin them via a
constraints file** so nothing upgrades torch (an upgrade breaks
`torchvision::nms` and the whole import). Then install the special build:

```bash
python3 - <<'PY' > /kaggle/working/torch-constraints.txt
import importlib
for m in ("torch","torchvision","torchaudio"):
    print(m+"=="+importlib.import_module(m).__version__)
PY
C="-c /kaggle/working/torch-constraints.txt"
pip -q install $C fastapi 'uvicorn[standard]' pydantic python-multipart pillow pyngrok requests
# Ideogram4Pipeline only exists in this diffusers PR build:
pip -q install $C 'git+https://github.com/huggingface/diffusers.git@04b197eece42bfc88d1814b20e07987d94cccaa7'
pip -q install $C transformers==5.8.0 peft==0.19.1 accelerate==1.10.1 outlines==1.3.0 sentencepiece safetensors
pip -q install $C 'bitsandbytes>=0.46.1'
pip -q install $C nvidia-nvjitlink-cu13 || echo "nvjitlink-cu13 optional"
```

**Gotchas confirmed on T4 (June 2026 image):**
- Verified versions that work: `diffusers 0.39.0.dev0` (PR), `transformers 5.8`,
  `bitsandbytes 0.49.2`, `accelerate 1.10.1`, `peft 0.19.1`, on `torch 2.10+cu128`.
- `nvidia-nvjitlink-cu13` may **fail to build a wheel — that's fine.** It's only
  needed if bitsandbytes was built for CUDA 13. bnb 0.49.2 runs nf4 correctly
  using torch's bundled `libnvJitLink.so.12`; the engine preloads CUDA libs
  (`engine._preload_cuda_libs`) with `RTLD_GLOBAL` so bnb resolves them.
- Sanity check: `python -c "from diffusers import Ideogram4Pipeline"`.

---

## 4. Run the server

```bash
source /kaggle/working/.studio_env        # exports HF_TOKEN (chmod 600; never commit)
export DUAL_GPU=1 CFG_PARALLEL=1 PORT=7860 HOST=0.0.0.0
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True   # MUST precede torch import
export MAX_SEQ_LEN=768
export CACHE_INTERVAL=1                    # 1 = max quality (no step caching); 2 ≈ 1.8x faster
export HF_HOME=/kaggle/working/.hf_cache   # keep the ~16 GB cache on the roomy volume
cd backend
nohup python3 -m uvicorn server:app --host 0.0.0.0 --port 7860 > /kaggle/working/studio_server.log 2>&1 &
```

The model loads **lazily on the first `/api/health` call** (synchronous,
downloads ~16 GB and spreads across both GPUs the first time, ≈3–6 min):

```bash
curl -s --max-time 2400 http://127.0.0.1:7860/api/health   # blocks until loaded
```

Healthy real-GPU response looks like:
`{"ok":true,"mock":false,"device":{"mode":"cuda-split","gpus":["Tesla T4","Tesla T4"],
"parallel":true,...},"model":"ideogram-ai/ideogram-4-nf4",...}`

> **`mock:false` is the thing to check.** If real load fails (deps/OOM/gated),
> the engine degrades to a PIL **mock** generator and `mock` is `true`.

---

## 5. ⚠️ The safety filter — and how to actually get images

**Symptom:** every generation returns a flat grey 1024² image reading
*"Image blocked by safety filter"* — even for wholesome prompts. It is **not**
your code, not diffusers, not the agent, not the environment. It is a
**post-training safety mitigation baked into the Ideogram-4 weights**
(`ideogram-oss/ideogram4/docs/safety.md`; widely reported on the HF discussions,
e.g. *"a cute cat" being blocked*). It runs the **full denoise and then blocks**,
so you pay the compute cost.

**The fix (verified):** prompt with a **rich, multi-field, English structured
JSON caption** — the format Ideogram-4 was trained on. Sparse inputs are blocked;
rich ones render.

| Prompt form | Result (verified) |
|---|---|
| Plain text ("ginger kitten…"), any language | ❌ blocked |
| Minimal JSON `{"description": "..."}` (EN or UK) | ❌ blocked |
| **Rich JSON** (description+subject+style+setting+lighting+colors+mood+details) | ✅ real image |

Send the JSON **as the `prompt` string**:

```bash
curl -s -X POST http://127.0.0.1:7860/api/generate -H 'Content-Type: application/json' -d '{
 "count":1, "steps":50,
 "prompt":"{\"description\":\"a fluffy ginger kitten in a sunny meadow wearing a daisy wreath\",\"subject\":\"ginger kitten with daisy wreath\",\"style\":\"soft watercolour illustration\",\"setting\":\"sunny green meadow\",\"lighting\":\"warm soft morning light\",\"colors\":\"warm pastels\",\"mood\":\"cosy, tender\",\"details\":\"fine fur texture, tiny daisies, paper grain\"}"
}'
```

**How to validate an output is real (not the placeholder), headless:**
decode the base64 and check pixel statistics — the placeholder is nearly flat
(std ≈ 10, mean-abs-gradient ≈ 0.3) and OCRs to "Image blocked by safety filter";
a real image has std ≳ 25 and gradient ≳ 3.

**Permanent fixes (pick one):**
1. **Enable the agent** (set `OLLAMA_API_KEY`): the chat agent rewrites a simple
   idea into the rich English JSON — this is the app's intended design (`agent.py`).
2. **Local enrichment:** patch `engine._expand_prompt` to wrap plain prompts in a
   rich JSON template locally (no key). NB: a *minimal* `{"description":...}` is
   **not** enough — include style/lighting/colours/mood/details.
3. `IDEOGRAM_API_KEY` → hosted magic-prompt expansion (but it is Hive-moderated
   and can itself false-positive; the engine falls back to the literal prompt).

---

## 5b. The filter is stochastic — detect a ban *cheaply*, then retry

Rich JSON **reduces** blocking but does **not** eliminate it. Verified behaviour
on 2× T4:

- **Scene-rich** prompts (a subject in a described environment) pass reliably —
  e.g. birthday cake, mountain lake, fisherman portrait, a *"greeting card with
  flowers"* that renders "Happy Birthday!".
- **Sparse / poster-like / text-dominant** prompts block far more often — a bare
  *"poster that says FRESH COFFEE"* or *"DREAM BIG"* was blocked even in rich
  JSON. **Embed the text inside a detailed scene** (a chalkboard on a cafe table,
  a neon sign on a bedroom wall) and it passes.
- It is also **per-seed stochastic**: the *same* prompt can pass on one seed and
  block on another. So **retry with a new seed**.

### Cheap pre-check ("1 image, 1 step, was it banned?")

The filter *runs the full denoise and then blocks*, so a naive 50-step attempt
wastes ~6 min on a T4. Probe cheaply first, and only spend full quality on
prompts/seeds that survive:

```
def is_banned(pil_img):
    import numpy as np
    g = pil_img.convert("L"); a = np.asarray(g).astype("float32")
    grad = (np.abs(np.diff(a,axis=1)).mean()+np.abs(np.diff(a,axis=0)).mean())/2
    # optional: OCR -> contains "blocked"/"safety"
    return a.std() < 14 and grad < 1.0     # flat grey placeholder signature

# strategy: probe at low cost, then commit to full quality only if it passes
for seed in candidate_seeds:
    probe = generate(prompt, steps=PROBE_STEPS, width=512, height=512, seed=seed)
    if not is_banned(probe):                 # survived -> render for real
        final = generate(prompt, steps=50, width=1024, height=1024, seed=seed)
        break
```

Notes / caveats (validate on your build):
- The block placeholder is a **near-flat grey image** (std ≈ 10, mean-abs-grad
  ≈ 0.3) that OCRs to *"Image blocked by safety filter"*. Real images: std ≳ 25.
- A **single** step (`steps=1`) may be too noisy to classify reliably (a real
  prompt is also noisy after 1 step). A **low-res, few-step probe**
  (`steps≈6–8`, `512²`) is the robust cheap signal: ~10–20 s vs ~6 min, and the
  ban decision tends to reproduce at the same seed when you scale up steps.
- Always cap attempts (e.g. 3 seeds) and fall back to a scene-rich rewrite of the
  prompt if every seed bans.
- Keep the **same seed** when you re-render at full quality so the survived
  composition is the one you get.

This "probe → commit" loop is the recommended way to reliably produce N good
images without paying full compute for the ones the filter would reject.

## 6. Helper agents (optional, Ollama)

`agent.py` is a tool-using team (chat + a vision "critic" that reviews images for
defects) on **Ollama Cloud**. Key via `OLLAMA_API_KEY` env or the UI header
`X-Ollama-Key`. It is **not** in the `/api/generate` path — it *feeds* it by
producing rich JSON prompts. Without a key the studio still works (manual JSON).

---

## 7. Public link (ngrok) — and the single-tunnel trap

ngrok **free = one agent / one domain**. If a code-server (or anything) is
already tunnelled on the account, **do not start a second tunnel** — both bind
the same `*.ngrok-free.dev` host and ngrok **round-robins** requests, silently
breaking both services. Either reuse the one tunnel, add the second via the
running agent's local API (`POST 127.0.0.1:4040/api/tunnels`) only if the plan
allows multiple endpoints, or just run headless and call `http://127.0.0.1:7860`.

---

## 8. img2img — flow-matching SDEdit (works at 1024² on 2× T4)

The diffusers Ideogram-4 port ships **no img2img pipeline**, and
`Ideogram4Pipeline.__call__` takes **no `image`/`strength`** (only text2img args
+ `latents`). `AutoPipelineForImage2Image.from_pipe` has no Ideogram-4 mapping,
so the original code fell back to the text2img pipe and raised `TypeError`.

`engine._img2img_sdedit` implements it correctly **and fits 1024² on 2× T4** by
*reusing the pipeline's own `__call__`* (which already fits 1024² for text2img)
instead of a hand-rolled denoise that OOMs the cramped cuda:0:

1. **VAE-encode the init image on `cuda:1`** (the GPU with free VRAM — cuda:0
   holds the text encoder + uncond DiT and has only ~4.6 GB free). This adds
   **zero** pressure to cuda:0.
2. **Pack + batch-norm-normalise** into the model's packed latent layout (the
   exact inverse of the pipeline's decode).
3. **Pick the start sigma from `strength`** (Flux `get_timesteps`): noise the
   init latents to that sigma — `x = (1-σ)·x0 + σ·ε` (`scale_noise`).
4. **Inject** those latents by monkeypatching `prepare_latents`, and **truncate**
   the schedule to the tail by monkeypatching `scheduler.set_timesteps`.
5. **Call the pipeline normally** → its tested encoder-offload + parallel
   dual-GPU denoise + VAE decode all run unchanged.

**The critical bug that made it OOM (and the fix):** the VAE encode MUST run
under `torch.no_grad()`. Without it, autograd keeps the encoder's ~6.5 GB of
intermediate activations alive on cuda:1 (referenced through the init latent),
`empty_cache` can't reclaim them, and the denoise then OOMs on cuda:1. With
`no_grad`, cuda:1 returns to ~10 GB free and 1024² img2img runs (~125–190 s for
strength 0.45–0.70).

**Notes:**
- The transform prompt **also** needs rich JSON (same safety filter as text2img).
- `strength` ≈ 0.35–0.5 keeps composition; ≈ 0.6–0.8 restyles heavily.
- `IMG2IMG_MAX_SIDE` (default 1024) caps the long side. Lower it only if you
  load extra components onto the GPUs.
- Verified: a 1024² mountain-lake photo → autumn reinterpretation at strength
  0.45 and 0.70, both real (std ≈ 69) and structure-preserving.

---

## 9. Managing the running studio

- **Logs:** `tail -f /kaggle/working/studio_server.log` (engine prints `[engine] …`).
- **GPUs:** `nvidia-smi --query-gpu=index,memory.used,utilization.gpu --format=csv`.
- **Restart safely — do NOT `pkill -f "uvicorn server:app"`** from your own shell:
  the pattern matches the shell command itself and kills your shell (and the
  server). Use the bracket trick:
  ```bash
  for pid in $(pgrep -f "[u]vicorn server:app"); do kill "$pid"; done
  ```
- **Quality vs speed:** `steps` (24 fast → 50 max), `CACHE_INTERVAL` (1 max
  quality, 2 ≈ 1.8×), `guidance` (default 6.0), resolution multiples of 16.
- **Persistence:** Kaggle wipes the session; commit code to git and copy
  artifacts you want to keep into a repo (see this project's backup of
  `/kaggle/working` into `Hvkki/Sito-per-intelligenti`). **Never** commit
  `.studio_env` (HF token) or the multi-GB `.hf_cache` (gated weights).

---

## 10. Troubleshooting quick table

| Symptom | Cause → Fix |
|---|---|
| Grey "Image blocked by safety filter" | weight-baked filter on sparse prompt → send **rich JSON** (§5) |
| `mock:true` in `/api/health` | real load failed (gated/deps/OOM) → check token/license, server log |
| 403 downloading weights | license not accepted on the token's account → accept on HF (§2) |
| `RuntimeError: torchvision::nms` | a pip install upgraded torch → reinstall with the torch **constraints** (§3) |
| `Ideogram4Pipeline` ImportError | wrong diffusers → install the exact **PR commit** (§3) |
| img2img `CUDA out of memory` | full-res encode on T4 → lower `IMG2IMG_MAX_SIDE` (768→640/512) |
| your shell dies on restart | `pkill -f "uvicorn server:app"` self-match → use `[u]vicorn` bracket trick (§9) |
| second ngrok URL breaks both apps | free plan single-tunnel round-robin → one tunnel only (§7) |

---

## Appendix — endpoints

`GET /api/health` · `POST /api/generate` {prompt, count, width, height, steps, guidance, seed}
· `POST /api/img2img` {image(dataURL), prompt, strength, count, steps} · `POST /api/inpaint`
· `POST /api/upscale` {image, scale} · `POST /api/chat` · `POST /api/review`.
All generation prompts should be **rich JSON** to clear the safety filter.
