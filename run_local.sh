#!/usr/bin/env bash
# Локальний запуск (демо-режим без GPU) — для перевірки інтерфейсу.
# Local run (MOCK mode, no GPU) — for testing the UI/flow.
set -e
cd "$(dirname "$0")"

python3 -m venv .venv 2>/dev/null || true
# shellcheck disable=SC1091
source .venv/bin/activate

pip install -q --upgrade pip
pip install -q fastapi "uvicorn[standard]" pydantic python-multipart pillow

export MOCK_MODE=1            # без GPU — генеруємо красиві заглушки
export PORT="${PORT:-7860}"

echo "▶ Відкрий у браузері:  http://localhost:${PORT}"
cd backend
python server.py
