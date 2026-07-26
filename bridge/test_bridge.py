#!/usr/bin/env python3
"""Tests for the Kiro bridge.

Runs the real server in a background thread on an ephemeral socket and drives it
over HTTP. Auth, validation and privacy checks always run. The one test that
invokes kiro-cli is skipped unless KIRO_API_KEY is set and kiro-cli is installed,
so this stays runnable in CI.

    python3 bridge/test_bridge.py
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kiro_bridge  # noqa: E402

TOKEN = "0123456789abcdef0123456789abcdef"
failures: list[str] = []
live_enabled = bool(os.environ.get("KIRO_API_KEY", "").strip()) and shutil.which("kiro-cli") is not None


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  ok   {name}")
    else:
        print(f"  FAIL {name} {detail}")
        failures.append(name)


def request(server_url: str, payload: dict | None, token: str | None = TOKEN, timeout: int = 240):
    """Returns (status, parsed_body)."""
    url = f"{server_url}/plan" if payload is not None else f"{server_url}/health"
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
    if data:
        req.add_header("content-type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"raw": raw}


def main() -> int:
    kiro_bridge.Handler.bridge_token = TOKEN
    kiro_bridge.Handler.default_model = "claude-haiku-4.5"

    # Port 0 lets the OS pick a free one, so tests never collide.
    server = ThreadingHTTPServer(("127.0.0.1", 0), kiro_bridge.Handler)
    base = f"http://127.0.0.1:{server.server_address[1]}"
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    try:
        print("pure functions:")
        noisy = "\x1b[?25l\u25b0\u25b1 Thinking... \x1b[?25h{\"decision\":\"done\"}"
        cleaned = kiro_bridge.strip_terminal_noise(noisy)
        # Control codes and spinner glyphs go; ordinary prose stays, because the
        # client's JSON extractor already tolerates text around the object.
        check("strips escape codes", "\x1b" not in cleaned, repr(cleaned))
        check("strips spinner glyphs", "\u25b0" not in cleaned and "\u25b1" not in cleaned, repr(cleaned))
        check("keeps the JSON intact", '{"decision":"done"}' in cleaned, repr(cleaned))
        check("constant-time compare accepts equal", kiro_bridge._equals("abc", "abc"))
        check("constant-time compare rejects different", not kiro_bridge._equals("abc", "abd"))

        print("\nhealth:")
        status, body = request(base, None, token=None)
        check("health needs no auth", status == 200 and body.get("ok") is True, str(body))
        check("health leaks no secrets", TOKEN not in json.dumps(body) and "ksk_" not in json.dumps(body))

        print("\nauthentication:")
        status, _ = request(base, {"prompt": "x"}, token=None)
        check("no token is rejected", status == 401)
        status, _ = request(base, {"prompt": "x"}, token="wrong-token-entirely-different")
        check("wrong token is rejected", status == 401)

        print("\nvalidation:")
        status, body = request(base, {}, timeout=30)
        check("missing prompt is rejected", status == 400, str(body))
        status, body = request(base, {"prompt": "x", "model": "evil-model"}, timeout=30)
        check("unsupported model is rejected", status == 400 and "model" in body.get("error", ""), str(body))
        status, body = request(base, {"prompt": "x", "effort": "ludicrous"}, timeout=30)
        check("unsupported effort is rejected", status == 400, str(body))

        print("\nlive kiro-cli:")
        if not live_enabled:
            print("  SKIPPED: set KIRO_API_KEY and install kiro-cli to run the live check")
        else:
            prompt = (
                "GOAL: submit the search\n"
                "SCREEN:\n"
                '  [2] edit_text desc="Search" "weather" (tap,type)\n'
                '  [3] button desc="Submit search" (tap)\n'
                'Reply with ONE JSON object: '
                '{"decision":"act","why":"<r>","step":{"action":"tap","selector":{"desc":"<d>"}}}'
            )
            status, body = request(
                base,
                {"system": "You reply with exactly one JSON object and no other text.", "prompt": prompt},
            )
            check("live plan returns 200", status == 200, str(body)[:200])
            text = body.get("text", "")
            check("response carries text", bool(text), str(body)[:200])
            check("reports elapsed time", isinstance(body.get("elapsedMs"), int), str(body)[:120])
            try:
                start = text.index("{")
                decision = json.loads(text[start : text.rindex("}") + 1])
                selector = decision.get("step", {}).get("selector", {})
                check(
                    "kiro chose the submit button by description",
                    selector.get("desc") == "Submit search",
                    f"got {selector}",
                )
            except (ValueError, json.JSONDecodeError) as error:
                check("live reply parses as JSON", False, f"{error}: {text[:160]}")
    finally:
        server.shutdown()
        server.server_close()

    print()
    if failures:
        print(f"FAILED: {len(failures)} check(s): {', '.join(failures)}")
        return 1
    print("all bridge checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
