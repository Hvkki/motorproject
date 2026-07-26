#!/usr/bin/env python3
"""Expose `kiro-cli` as a tiny HTTPS-frontable JSON endpoint.

Why this exists: KIRO_API_KEY authenticates a local `kiro-cli` process, not a
hosted API. A phone cannot run the CLI, so this runs it on a machine you control
(a VM, a home server, a Kaggle/Colab session behind a tunnel) and answers one
question: "given this screen, what is the next action?"

Security model, and the reason the two credentials are separate:

  * KIRO_API_KEY stays here and is never sent to a client.
  * Clients authenticate with FINGERTIP_BRIDGE_TOKEN, a different secret you can
    rotate freely. A compromised phone therefore costs you a token, not your Kiro
    subscription.

Privacy: prompts contain a description of the user's screen. Nothing about a
request body is ever logged - only method, path, status and duration.

Usage:
    export KIRO_API_KEY=ksk_...                  # your Kiro key
    export FINGERTIP_BRIDGE_TOKEN=$(openssl rand -hex 32)
    python3 bridge/kiro_bridge.py --port 8000

Then expose it over TLS, for example:
    ngrok http 8000
and point the app at the https:// URL. Plain HTTP is refused by the app.

Endpoints:
    GET  /health -> {"ok":true,"model":...}   no auth, no secrets
    POST /plan   -> {"text":"<model reply>"}  Bearer auth required
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

DEFAULT_MODEL = "claude-haiku-4.5"
DEFAULT_EFFORT = "low"
MAX_BODY_BYTES = 512 * 1024
CLI_TIMEOUT_SECONDS = 180

# kiro-cli writes terminal control codes and spinner frames even with
# --no-interactive, so they have to be stripped before returning the text.
ANSI = re.compile(r"\x1B\[[0-9;?]*[ -/]*[@-~]|\x1B\][^\x07\x1B]*(?:\x07|\x1B\\)")
SPINNER = re.compile(r"[\u2800-\u28FF\u2596-\u259F\u25A0-\u25FF]+")

ALLOWED_MODELS = {
    "auto",
    "claude-haiku-4.5",
    "claude-sonnet-4.6",
    "claude-sonnet-5",
    "claude-opus-5",
    "gpt-5.6-luna",
    "gpt-5.6-sol",
    "gpt-5.6-terra",
    "deepseek-3.2",
    "minimax-m2.5",
    "glm-5",
    "qwen3-coder-next",
}
ALLOWED_EFFORT = {"low", "medium", "high", "xhigh", "max"}


def strip_terminal_noise(text: str) -> str:
    return SPINNER.sub("", ANSI.sub("", text)).strip()


class BridgeError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


def run_kiro(prompt: str, model: str, effort: str) -> str:
    """Invokes kiro-cli once and returns its cleaned stdout."""
    executable = shutil.which("kiro-cli")
    if not executable:
        raise BridgeError(503, "kiro-cli is not installed on the bridge host")

    command = [
        executable,
        "chat",
        "--no-interactive",
        "--model",
        model,
        "--effort",
        effort,
        # The prompt must be the positional argument: kiro-cli does not consume
        # piped stdin as context and will waste a turn trying to shell out for it.
        prompt,
    ]
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=CLI_TIMEOUT_SECONDS,
            # KIRO_API_KEY reaches the CLI through the inherited environment, never
            # on the command line where it would show up in the process table.
            env=os.environ.copy(),
        )
    except subprocess.TimeoutExpired:
        raise BridgeError(504, f"kiro-cli timed out after {CLI_TIMEOUT_SECONDS}s") from None

    if completed.returncode != 0:
        detail = strip_terminal_noise(completed.stderr or completed.stdout)[:300]
        if completed.returncode == 1:
            raise BridgeError(502, f"kiro-cli failed; check KIRO_API_KEY. {detail}")
        raise BridgeError(502, f"kiro-cli exited {completed.returncode}. {detail}")

    text = strip_terminal_noise(completed.stdout)
    if not text:
        raise BridgeError(502, "kiro-cli returned no output")
    return text


class Handler(BaseHTTPRequestHandler):
    server_version = "FingertipKiroBridge/1.0"
    bridge_token: str = ""
    default_model: str = DEFAULT_MODEL

    def log_message(self, fmt: str, *args: Any) -> None:
        # Deliberately terse: request bodies describe the user's screen and must
        # never be written to a log.
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.rstrip("/") == "/health":
            # No auth and no secrets: safe to probe from a tunnel.
            self._send(200, {"ok": True, "model": self.default_model})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        started = time.monotonic()
        try:
            if self.path.rstrip("/") != "/plan":
                raise BridgeError(404, "not found")
            self._authenticate()
            request = self._read_json()

            prompt = request.get("prompt")
            if not isinstance(prompt, str) or not prompt.strip():
                raise BridgeError(400, "prompt is required")

            system = request.get("system")
            full_prompt = f"{system.strip()}\n\n{prompt}" if isinstance(system, str) and system.strip() else prompt

            model = request.get("model") or self.default_model
            effort = request.get("effort") or DEFAULT_EFFORT
            if model not in ALLOWED_MODELS:
                raise BridgeError(400, f"unsupported model: {model}")
            if effort not in ALLOWED_EFFORT:
                raise BridgeError(400, f"unsupported effort: {effort}")

            text = run_kiro(full_prompt, model, effort)
            elapsed_ms = int((time.monotonic() - started) * 1000)
            self._send(200, {"text": text, "model": model, "elapsedMs": elapsed_ms})
        except BridgeError as error:
            self._send(error.status, {"error": error.message})
        except Exception:  # noqa: BLE001
            # Never surface an internal traceback: it can quote request content.
            self._send(500, {"error": "internal bridge error"})

    def _authenticate(self) -> None:
        header = self.headers.get("Authorization", "")
        expected = f"Bearer {self.bridge_token}"
        # Constant-time-ish comparison to avoid leaking the token by timing.
        if len(header) != len(expected) or not _equals(header, expected):
            raise BridgeError(401, "invalid bridge token")

    def _read_json(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise BridgeError(400, "invalid Content-Length") from None
        if length <= 0:
            raise BridgeError(400, "empty body")
        if length > MAX_BODY_BYTES:
            raise BridgeError(413, "body too large")
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise BridgeError(400, "body must be JSON") from None
        if not isinstance(payload, dict):
            raise BridgeError(400, "body must be a JSON object")
        return payload


def _equals(left: str, right: str) -> bool:
    result = 0
    for a, b in zip(left, right):
        result |= ord(a) ^ ord(b)
    return result == 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="bind address (default: loopback)")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--model", default=os.environ.get("FINGERTIP_MODEL", DEFAULT_MODEL))
    parser.add_argument("--self-test", action="store_true", help="check configuration and exit")
    args = parser.parse_args()

    token = os.environ.get("FINGERTIP_BRIDGE_TOKEN", "").strip()
    if len(token) < 16:
        print(
            "FINGERTIP_BRIDGE_TOKEN must be set and at least 16 characters.\n"
            "  export FINGERTIP_BRIDGE_TOKEN=$(openssl rand -hex 32)",
            file=sys.stderr,
        )
        return 2
    if not os.environ.get("KIRO_API_KEY", "").strip():
        print("KIRO_API_KEY is not set; kiro-cli will fail to authenticate.", file=sys.stderr)
        return 2
    if args.model not in ALLOWED_MODELS:
        print(f"unsupported model: {args.model}", file=sys.stderr)
        return 2

    Handler.bridge_token = token
    Handler.default_model = args.model

    if args.self_test:
        print(f"configuration OK: model={args.model}, kiro-cli={'found' if shutil.which('kiro-cli') else 'MISSING'}")
        return 0

    # Loopback by default. Expose it through a TLS tunnel rather than binding
    # 0.0.0.0: these requests carry screen descriptions.
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Fingertip Kiro bridge on http://{args.host}:{args.port} (model {args.model})", file=sys.stderr)
    print("Expose with TLS, e.g. `ngrok http %d`, then point the app at the https URL." % args.port, file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
