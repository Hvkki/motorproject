"""
============================================================================
  МАМИНА СТУДІЯ — запуск на Kaggle (2x NVIDIA T4)
============================================================================

Цей файл призначений для запуску в Kaggle Notebook з увімкненим
прискорювачем «GPU T4 x2». Він:

  1. встановлює залежності (Ideogram 4 + diffusers + ngrok);
  2. забирає код застосунку (з GitHub або з доданого Kaggle-датасету);
  3. авторизується в Hugging Face (ваги Ideogram 4 — закриті/gated);
  4. запускає FastAPI-сервер (фронтенд + API) на двох відеокартах;
  5. відкриває публічне посилання через ngrok — його ви даєте мамі.

----------------------------------------------------------------------------
ПЕРЕД ЗАПУСКОМ (один раз):
----------------------------------------------------------------------------
A) Kaggle: Settings → Accelerator → "GPU T4 x2", Internet → "On".
B) Прийміть ліцензію моделі (натисніть "Agree and access repository"):
     https://huggingface.co/ideogram-ai/ideogram-4-nf4-diffusers
C) Додайте 2 секрети у Kaggle (Add-ons → Secrets):
     HF_TOKEN     — токен Hugging Face (huggingface.co/settings/tokens)
     NGROK_TOKEN  — токен ngrok       (dashboard.ngrok.com → Your Authtoken)
D) Вкажіть REPO_URL нижче (куди ви запушили цей проєкт), АБО додайте
   проєкт як Kaggle-датасет і він зчитається з /kaggle/input.

Запуск: Run All. Дочекайтесь рядка з посиланням  https://....ngrok-free.app
============================================================================
"""

import os
import subprocess
import sys
import threading
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# 0. НАЛАШТУВАННЯ — змініть за потреби
# ---------------------------------------------------------------------------
REPO_URL = "https://github.com/Hvkki/motorproject.git"  # <- ваш репозиторій
APP_DIRNAME = "motorproject"
PORT = 7860

# ---------------------------------------------------------------------------
# 1. СЕКРЕТИ (Kaggle Secrets → fallback на змінні середовища)
# ---------------------------------------------------------------------------
def _get_secret(name: str) -> str | None:
    try:
        from kaggle_secrets import UserSecretsClient

        return UserSecretsClient().get_secret(name)
    except Exception:
        return os.environ.get(name)


HF_TOKEN = _get_secret("HF_TOKEN")
NGROK_TOKEN = _get_secret("NGROK_TOKEN")
# Необовʼязково: ключ для «магічних» підказок (кращі результати з простого тексту)
IDEOGRAM_API_KEY = _get_secret("IDEOGRAM_API_KEY")
# Необовʼязково: ключ Ollama Cloud для агентів-помічників
OLLAMA_API_KEY = _get_secret("OLLAMA_API_KEY")

assert HF_TOKEN, "Додайте секрет HF_TOKEN (токен Hugging Face)."
assert NGROK_TOKEN, "Додайте секрет NGROK_TOKEN (токен ngrok)."

os.environ["HF_TOKEN"] = HF_TOKEN
os.environ["HUGGING_FACE_HUB_TOKEN"] = HF_TOKEN
if IDEOGRAM_API_KEY:
    os.environ["IDEOGRAM_API_KEY"] = IDEOGRAM_API_KEY
if OLLAMA_API_KEY:
    os.environ["OLLAMA_API_KEY"] = OLLAMA_API_KEY
os.environ["DUAL_GPU"] = "1"          # використати обидві T4
os.environ["PORT"] = str(PORT)


# ---------------------------------------------------------------------------
# 2. ЗАЛЕЖНОСТІ
# ---------------------------------------------------------------------------
def sh(cmd: str) -> None:
    print(f"$ {cmd}")
    subprocess.run(cmd, shell=True, check=True)


print("⏳ Встановлюю залежності… (1–3 хв)")
sh("pip -q install --upgrade pip")
# Веб-стек + тунель
sh("pip -q install fastapi 'uvicorn[standard]' pydantic python-multipart pillow pyngrok requests")
# Інференс-стек для nf4 на T4 (torch вже є на Kaggle GPU-образі)
sh("pip -q install 'diffusers>=0.31' 'transformers>=4.44' accelerate 'bitsandbytes>=0.43' sentencepiece safetensors")
# Офіційний пакет Ideogram 4 (кастомний пайплайн + run_inference.py)
sh("pip -q install 'git+https://github.com/ideogram-oss/ideogram4.git' || echo 'ideogram4 package optional — diffusers path still works'")


# ---------------------------------------------------------------------------
# 3. КОД ЗАСТОСУНКУ (GitHub або Kaggle-датасет)
# ---------------------------------------------------------------------------
def locate_app() -> Path:
    # 3a. чи доданий датасет із проєктом?
    for base in Path("/kaggle/input").glob("**/backend/server.py"):
        print(f"✔ Знайдено проєкт у датасеті: {base.parent.parent}")
        return base.parent.parent
    # 3b. інакше клонуємо з GitHub
    target = Path("/kaggle/working") / APP_DIRNAME
    if not target.exists():
        if "USERNAME" in REPO_URL:
            raise SystemExit(
                "❌ Вкажіть REPO_URL угорі скрипту або додайте проєкт як Kaggle-датасет."
            )
        sh(f"git clone --depth 1 {REPO_URL} {target}")
    return target


APP_DIR = locate_app()
BACKEND = APP_DIR / "backend"
sys.path.insert(0, str(BACKEND))
print(f"✔ Код застосунку: {APP_DIR}")


# ---------------------------------------------------------------------------
# 4. ДІАГНОСТИКА GPU
# ---------------------------------------------------------------------------
import torch  # noqa: E402

print(f"CUDA доступна: {torch.cuda.is_available()}")
for i in range(torch.cuda.device_count()):
    p = torch.cuda.get_device_properties(i)
    print(f"  GPU {i}: {p.name}, {p.total_memory / 1e9:.1f} ГБ")
if torch.cuda.device_count() < 2:
    print("⚠️  Знайдено менше 2 GPU. Увімкніть 'GPU T4 x2' у Settings → Accelerator.")


# ---------------------------------------------------------------------------
# 5. ЗАПУСК СЕРВЕРА (у фоні) + ПРОГРІВ МОДЕЛІ
# ---------------------------------------------------------------------------
def run_server() -> None:
    import uvicorn

    # імпортуємо застосунок із backend/server.py
    from server import app  # noqa: WPS433

    uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="info")


server_thread = threading.Thread(target=run_server, daemon=True)
server_thread.start()
print("⏳ Сервер стартує, модель завантажується на 2 GPU (перший раз ~3–6 хв)…")

# чекаємо, поки /api/health відповість «готово»
import requests  # noqa: E402

for attempt in range(120):
    try:
        d = requests.get(f"http://127.0.0.1:{PORT}/api/health", timeout=5).json()
        if d.get("ok"):
            mode = "ДЕМО (без GPU)" if d.get("mock") else d.get("device", {}).get("mode", "ok")
            print(f"✔ Сервер готовий. Режим: {mode}")
            break
    except Exception:
        pass
    time.sleep(5)


# ---------------------------------------------------------------------------
# 6. ПУБЛІЧНЕ ПОСИЛАННЯ (ngrok)
# ---------------------------------------------------------------------------
from pyngrok import conf, ngrok  # noqa: E402

conf.get_default().auth_token = NGROK_TOKEN
public_url = ngrok.connect(PORT, "http").public_url

print("\n" + "=" * 64)
print("  🎉 МАМИНА СТУДІЯ ГОТОВА!")
print("  Дайте мамі це посилання:")
print(f"      {public_url}")
print("=" * 64)
print("  (Тримайте цей ноутбук відкритим — поки він працює, працює й студія.)")
print("=" * 64 + "\n")

# тримаємо ноутбук живим
try:
    while True:
        time.sleep(60)
        print("… студія працює ✓", flush=True)
except KeyboardInterrupt:
    ngrok.disconnect(public_url)
    print("Зупинено.")
