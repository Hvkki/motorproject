"""
Агенти-помічники на базі Ollama Cloud.

A small team of tool-using *agents* (no personal names) that drive the studio:

* **Агент-помічник** (chat) — a warm Ukrainian conversation that asks
  clarifying questions and, when ready, calls TOOLS to get things done.
* **Агент-критик** (review) — a vision pass that inspects freshly generated
  images for artifacts (extra fingers, broken faces, garbled text, …).

Tools the chat agent may call (returned in the `actions` array):
    generate    — малює зображення з тексту
    img2img     — перетворює прикріплене фото
    variations  — робить схожі варіанти останнього зображення
    upscale     — збільшує/покращує останнє зображення
    edit        — режим «обведи й зміни» на останньому зображенні
    review      — перевіряє останні зображення на дефекти

Auth: an Ollama Cloud Bearer key. It may come from the environment
(`OLLAMA_API_KEY`) OR be supplied per-request from the UI (header
`X-Ollama-Key`). The key is never hard-coded.
"""

from __future__ import annotations

import json
import os
import re
from typing import Any, Optional

import requests

DEFAULT_MODEL = os.environ.get("OLLAMA_MODEL", "gemma4:31b")
DEFAULT_BASE = os.environ.get("OLLAMA_BASE_URL", "https://ollama.com").rstrip("/")

VALID_TOOLS = {"generate", "img2img", "variations", "upscale", "edit", "review"}

CHAT_SYSTEM = """\
Ти — розумний агент-помічник у застосунку «Мамина Студія», де користувачка —
мама, немолода жінка — створює зображення за допомогою штучного інтелекту.
Спілкуйся дуже просто й тепло українською, ввічливо на «ви», без технічних слів.

Ти КЕРУЄШ командою інструментів (їх виконує застосунок). Доступні інструменти:
- generate   — намалювати зображення з опису
- img2img    — перетворити прикріплене фото (лише якщо фото додано)
- variations — зробити схожі варіанти останнього зображення
- upscale    — збільшити та зробити чіткішим останнє зображення
- edit       — увімкнути режим «обведи й зміни» на останньому зображенні
- review     — перевірити останні зображення на дефекти

Поведінка:
- Якщо ідея нечітка — постав 1–3 короткі прості питання і нічого не виконуй.
- Якщо все зрозуміло або просять «просто зроби» — ВИКОНАЙ потрібні інструменти.
- Можеш викликати кілька інструментів за раз (напр. generate, потім review).

Відповідай ВИКЛЮЧНО валідним JSON (без markdown, без пояснень):
{
  "reply": "тепле повідомлення українською",
  "questions": ["коротке питання"],
  "actions": [
    {"tool":"generate","prompt":{
        "description":"детальний опис сцени АНГЛІЙСЬКОЮ, 2–4 речення",
        "subject":"головний обʼєкт",
        "style":"художній стиль або техніка",
        "setting":"оточення / фон",
        "lighting":"освітлення",
        "colors":"кольорова палітра",
        "mood":"настрій",
        "details":"важливі дрібні деталі"
      },"count":4,"format":"square|landscape|portrait|banner"},
    {"tool":"img2img","prompt":{"description":"...","style":"...","details":"..."},"strength":0.6},
    {"tool":"variations"},
    {"tool":"upscale"},
    {"tool":"edit"},
    {"tool":"review"}
  ]
}
Правила:
- Бракує деталей → questions заповнені, actions = [].
- Достатньо деталей → actions заповнені, questions = [].
- Поле "prompt" у generate/img2img — це ДЕТАЛЬНИЙ структурований JSON-опис
  АНГЛІЙСЬКОЮ (модель Ideogram 4 навчена саме на таких структурованих підписах і
  слухається їх значно краще). Розгорни просту ідею користувачки у багатий
  конкретний опис: матеріали, освітлення, кольори, композицію, настрій, дрібні
  деталі. Описуй щедро та образно, але НЕ додавай того, чого користувачка явно
  не просила, і зберігай її задум.
- Не пиши, що вже все зроблено — інструменти виконає застосунок після відповіді.
"""

REVIEW_SYSTEM = """\
Ти — уважний агент-критик, який перевіряє згенеровані зображення на дефекти:
зайві або спотворені пальці/руки, скривлені обличчя чи очі, нечитабельний або
неправильний текст, дивні артефакти, обрізані обʼєкти. Будь доброзичливим і
говори дуже простою українською мовою.

Відповідай ВИКЛЮЧНО валідним JSON:
{
  "ok": true,
  "summary": "одне тепле речення-підсумок українською",
  "issues": [{"index":1,"problem":"що не так простими словами","fix":"коротка порада"}]
}
Нумеруй зображення з 1. Якщо все гарно — ok=true, issues=[]. Не вигадуй проблем.
"""


def _extract_json(text: str) -> dict[str, Any]:
    if not text:
        return {}
    text = text.strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.IGNORECASE | re.MULTILINE).strip()
    try:
        return json.loads(text)
    except Exception:  # noqa: BLE001
        pass
    start, end = text.find("{"), text.rfind("}")
    if start != -1 and end > start:
        try:
            return json.loads(text[start : end + 1])
        except Exception:  # noqa: BLE001
            return {}
    return {}


def _strip_data_url(b64: str) -> str:
    return b64.split(",", 1)[1] if "," in b64 else b64


class OllamaAgent:
    def __init__(self) -> None:
        self.base_url = DEFAULT_BASE
        self.env_key = os.environ.get("OLLAMA_API_KEY")
        self.model = DEFAULT_MODEL
        self.timeout = int(os.environ.get("OLLAMA_TIMEOUT", "120"))

    @property
    def available(self) -> bool:
        return bool(self.env_key)

    def _key(self, override: Optional[str]) -> Optional[str]:
        return (override or "").strip() or self.env_key

    # ---- low-level call --------------------------------------------------- #
    def _call(self, messages: list[dict], api_key: str, model: str, force_json: bool = True) -> str:
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        payload: dict[str, Any] = {"model": model, "messages": messages, "stream": False}
        if force_json:
            payload["format"] = "json"
        resp = requests.post(
            f"{self.base_url}/api/chat", headers=headers, json=payload, timeout=self.timeout
        )
        resp.raise_for_status()
        return resp.json().get("message", {}).get("content", "") or ""

    def ping(self, api_key: Optional[str] = None, model: Optional[str] = None) -> dict:
        key = self._key(api_key)
        if not key:
            return {"available": False, "reason": "no key"}
        try:
            r = requests.get(
                f"{self.base_url}/api/tags",
                headers={"Authorization": f"Bearer {key}"},
                timeout=15,
            )
            r.raise_for_status()
            names = [m["name"] for m in r.json().get("models", [])]
            mdl = model or self.model
            return {
                "available": True,
                "model": mdl,
                "model_found": mdl in names,
                "models": names,
            }
        except Exception as exc:  # noqa: BLE001
            return {"available": False, "reason": str(exc)}

    # ---- conversation ----------------------------------------------------- #
    def chat(
        self,
        history: list[dict],
        has_photo: bool = False,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
    ) -> dict:
        key = self._key(api_key)
        if not key:
            raise RuntimeError("no Ollama key")
        sys = CHAT_SYSTEM
        if has_photo:
            sys += "\nКористувачка прикріпила власне фото — доступний інструмент img2img."
        else:
            sys += "\nФото не прикріплено — інструмент img2img зараз недоступний."
        messages = [{"role": "system", "content": sys}] + history[-12:]
        raw = self._call(messages, key, model or self.model, force_json=True)
        data = _extract_json(raw)

        reply = data.get("reply") or "Розкажіть трішки більше, будь ласка 🌸"
        questions = data.get("questions") or []

        # Accept both `actions` (list) and legacy `action` (single).
        raw_actions = data.get("actions")
        if not raw_actions and data.get("action"):
            raw_actions = [data["action"]]
        actions = self._clean_actions(raw_actions, has_photo)

        return {"reply": reply, "questions": questions[:3], "actions": actions}

    def _clean_actions(self, raw_actions: Any, has_photo: bool) -> list[dict]:
        out: list[dict] = []
        if not isinstance(raw_actions, list):
            return out
        for a in raw_actions:
            if not isinstance(a, dict):
                continue
            tool = a.get("tool") or a.get("type")
            if tool not in VALID_TOOLS:
                continue
            if tool == "img2img" and not has_photo:
                continue
            item: dict[str, Any] = {"tool": tool}
            if tool in ("generate", "img2img", "variations"):
                p = a.get("prompt", "")
                # gemma now returns the prompt as a structured Ideogram-4 JSON
                # object; serialise it to the JSON-string caption the model
                # expects. Plain strings (older behaviour) pass through.
                if isinstance(p, dict):
                    p = {k: v for k, v in p.items() if str(v).strip()}
                    p = json.dumps(p, ensure_ascii=False)
                item["prompt"] = str(p).strip()
            if tool == "generate":
                item["count"] = max(1, min(int(a.get("count", 4) or 4), 6))
                item["format"] = a.get("format", "square")
                item["style"] = str(a.get("style", "") or "").strip()
                if not item["prompt"]:
                    continue
            if tool == "img2img":
                item["strength"] = float(a.get("strength", 0.6) or 0.6)
                if not item["prompt"]:
                    continue
            out.append(item)
        return out[:4]

    # ---- vision review ---------------------------------------------------- #
    def review(
        self,
        images_b64: list[str],
        prompt: str = "",
        api_key: Optional[str] = None,
        model: Optional[str] = None,
    ) -> dict:
        key = self._key(api_key)
        if not key:
            raise RuntimeError("no Ollama key")
        imgs = [_strip_data_url(b) for b in images_b64[:4]]
        user_text = (
            f"Ось {len(imgs)} зображень, створених за запитом: «{prompt}». "
            "Перевір кожне на дефекти й дай підсумок."
        )
        messages = [
            {"role": "system", "content": REVIEW_SYSTEM},
            {"role": "user", "content": user_text, "images": imgs},
        ]
        raw = self._call(messages, key, model or self.model, force_json=True)
        data = _extract_json(raw)
        issues = []
        for it in data.get("issues") or []:
            if isinstance(it, dict):
                issues.append(
                    {
                        "index": int(it.get("index", 1) or 1),
                        "problem": str(it.get("problem", "")).strip(),
                        "fix": str(it.get("fix", "")).strip(),
                    }
                )
        return {
            "ok": bool(data.get("ok", not issues)),
            "summary": str(data.get("summary", "")).strip()
            or ("Усе виглядає чудово! 💛" if not issues else "Знайшов кілька дрібниць."),
            "issues": issues,
        }


agent = OllamaAgent()
