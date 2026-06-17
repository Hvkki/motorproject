"""
FastAPI-сервер «Мамина Студія».

Serves BOTH the static Ukrainian frontend and the JSON API from one origin,
so on Kaggle a single ngrok URL gives your mom the whole app — no CORS, no
second link to manage.

Endpoints
---------
GET  /                 -> the web app (index.html)
GET  /api/health       -> engine status (mock vs dual-GPU, device layout)
POST /api/generate     -> text-to-image, returns N base64 images
POST /api/inpaint      -> "circle to modify" region regeneration
"""

from __future__ import annotations

import json
import threading
from typing import Optional

from fastapi import FastAPI, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from config import settings
from ideogram_engine import engine
from agent import agent

app = FastAPI(title="Мамина Студія", version="1.0.0")

# Allow the app to be embedded / called from anywhere (ngrok-friendly).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# --------------------------- request models ------------------------------- #
class GenerateRequest(BaseModel):
    prompt: str = Field(..., min_length=1, max_length=2000)
    negative: str = Field("", max_length=2000)
    count: int = Field(4, ge=1, le=8)
    width: Optional[int] = None
    height: Optional[int] = None
    steps: Optional[int] = None
    guidance: Optional[float] = None
    seed: Optional[int] = None


class InpaintRequest(BaseModel):
    image: str                       # data URL (the original image)
    mask: str                        # data URL (white = area to change)
    prompt: str = Field(..., min_length=1, max_length=2000)
    steps: Optional[int] = None
    guidance: Optional[float] = None
    seed: Optional[int] = None


class Img2ImgRequest(BaseModel):
    image: str                       # data URL (the uploaded photo)
    prompt: str = Field(..., min_length=1, max_length=2000)
    strength: float = Field(0.6, ge=0.05, le=0.95)
    count: int = Field(1, ge=1, le=8)
    steps: Optional[int] = None
    guidance: Optional[float] = None
    seed: Optional[int] = None


class UpscaleRequest(BaseModel):
    image: str                       # data URL
    scale: int = Field(2, ge=2, le=4)


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    messages: list[ChatMessage]
    has_photo: bool = False


class ReviewRequest(BaseModel):
    images: list[str]
    prompt: str = ""


class KeyTestRequest(BaseModel):
    key: str = ""
    model: Optional[str] = None


# --------------------------------- API ------------------------------------ #
@app.get("/api/health")
def health():
    engine.load()
    return {
        "ok": True,
        "mock": engine.mock,
        "device": engine.device_info,
        "model": settings.model_repo,
        "agent": {"available": agent.available, "model": agent.model},
        "defaults": {
            "width": settings.default_width,
            "height": settings.default_height,
            "steps": settings.default_steps,
            "guidance": settings.default_guidance,
            "max_batch": settings.max_batch,
        },
    }


@app.post("/api/chat")
def chat(
    req: ChatRequest,
    x_ollama_key: Optional[str] = Header(None),
    x_ollama_model: Optional[str] = Header(None),
):
    if not (agent.available or x_ollama_key):
        return JSONResponse({"error": "agent_unavailable"}, status_code=503)
    history = [{"role": m.role, "content": m.content} for m in req.messages]
    try:
        result = agent.chat(
            history, has_photo=req.has_photo, api_key=x_ollama_key, model=x_ollama_model
        )
        return JSONResponse(result)
    except Exception as exc:  # noqa: BLE001
        return JSONResponse({"error": str(exc)}, status_code=502)


@app.post("/api/review")
def review(
    req: ReviewRequest,
    x_ollama_key: Optional[str] = Header(None),
    x_ollama_model: Optional[str] = Header(None),
):
    if not (agent.available or x_ollama_key):
        return JSONResponse({"error": "agent_unavailable"}, status_code=503)
    if not req.images:
        return JSONResponse({"error": "no_images"}, status_code=422)
    try:
        return JSONResponse(
            agent.review(req.images, req.prompt, api_key=x_ollama_key, model=x_ollama_model)
        )
    except Exception as exc:  # noqa: BLE001
        return JSONResponse({"error": str(exc)}, status_code=502)


@app.post("/api/agent/test")
def agent_test(req: KeyTestRequest):
    """Validate an Ollama key entered in the UI."""
    return JSONResponse(agent.ping(api_key=req.key, model=req.model))


def _streamed(make_payload):
    """Run a slow engine call in a background thread while trickling keep-alive
    whitespace to the client, then emit the final JSON.

    Image generation on 2x T4 takes minutes; a single blocking HTTP request that
    long gets killed by the ngrok edge (the browser sees HTTP 503) even though
    the work succeeds. Streaming a space every few seconds keeps the tunnel
    active. Leading whitespace is valid JSON, so the frontend's `response.json()`
    parses the payload unchanged — no client changes needed.
    """
    box: dict = {}

    def work():
        try:
            box["payload"] = make_payload()
        except Exception as exc:  # noqa: BLE001
            box["payload"] = {"error": str(exc)}

    t = threading.Thread(target=work, daemon=True)
    t.start()

    def body():
        while t.is_alive():
            t.join(timeout=5)
            if t.is_alive():
                yield b" "
        yield json.dumps(box.get("payload", {"error": "no result"})).encode("utf-8")

    return StreamingResponse(body(), media_type="application/json")


@app.post("/api/generate")
def generate(req: GenerateRequest):
    def make():
        res = engine.generate(
            prompt=req.prompt,
            negative=req.negative,
            n=req.count,
            width=req.width,
            height=req.height,
            steps=req.steps,
            guidance=req.guidance,
            seed=req.seed,
        )
        return {
            "images": res.images_b64,
            "seeds": res.seeds,
            "elapsed": res.elapsed,
            "mock": res.mock,
            "width": res.width,
            "height": res.height,
        }

    return _streamed(make)


@app.post("/api/inpaint")
def inpaint(req: InpaintRequest):
    def make():
        res = engine.inpaint(
            image_b64=req.image,
            mask_b64=req.mask,
            prompt=req.prompt,
            steps=req.steps,
            guidance=req.guidance,
            seed=req.seed,
        )
        return {
            "images": res.images_b64,
            "seeds": res.seeds,
            "elapsed": res.elapsed,
            "mock": res.mock,
        }

    return _streamed(make)


@app.post("/api/img2img")
def img2img(req: Img2ImgRequest):
    def make():
        res = engine.img2img(
            image_b64=req.image,
            prompt=req.prompt,
            strength=req.strength,
            n=req.count,
            steps=req.steps,
            guidance=req.guidance,
            seed=req.seed,
        )
        return {
            "images": res.images_b64,
            "seeds": res.seeds,
            "elapsed": res.elapsed,
            "mock": res.mock,
            "width": res.width,
            "height": res.height,
        }

    return _streamed(make)


@app.post("/api/upscale")
def upscale(req: UpscaleRequest):
    res = engine.upscale(image_b64=req.image, scale=req.scale)
    return JSONResponse(
        {
            "images": res.images_b64,
            "elapsed": res.elapsed,
            "mock": res.mock,
            "width": res.width,
            "height": res.height,
        }
    )
# Mounted last so /api/* always wins. `html=True` serves index.html at "/".
if settings.frontend_dir.exists():
    @app.get("/")
    def index():
        return FileResponse(settings.frontend_dir / "index.html")

    app.mount("/", StaticFiles(directory=str(settings.frontend_dir), html=True), name="static")


if __name__ == "__main__":
    import uvicorn

    print(f"[server] frontend dir: {settings.frontend_dir}")
    uvicorn.run(app, host=settings.host, port=settings.port)
