#!/usr/bin/env python3
"""Static and dry-run validation for the Kaggle script kernel.

This does not need Kaggle credentials. It catches malformed metadata, accidental
GPU allocation, missing internet access, unsafe ADB tunnelling, syntax errors,
and drift between the kernel script and the repository layout before a push.
"""

from __future__ import annotations

import ast
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KERNEL_DIR = ROOT / "kaggle"
METADATA_FILE = KERNEL_DIR / "kernel-metadata.json"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    metadata = json.loads(METADATA_FILE.read_text(encoding="utf-8"))
    required = {"id", "title", "code_file", "language", "kernel_type"}
    require(required <= metadata.keys(), f"missing metadata fields: {sorted(required - metadata.keys())}")
    require(metadata["id"] == "hvkki/fingertip-android-validation", "unexpected Kaggle kernel id")
    require(metadata["language"] == "python", "kernel must use Python")
    require(metadata["kernel_type"] == "script", "kernel must be a script")
    require(metadata.get("is_private") is True, "validation kernel must default to private")
    require(metadata.get("enable_internet") is True, "kernel needs internet to clone and install Android SDK")
    require(metadata.get("enable_gpu") is False, "GPU does not accelerate Gradle or Android CPU emulation")

    code_file = KERNEL_DIR / metadata["code_file"]
    require(code_file.is_file(), f"code_file does not exist: {code_file}")
    source = code_file.read_text(encoding="utf-8")
    ast.parse(source, filename=str(code_file))

    lowered = source.lower()
    require("ngrok" not in lowered, "do not expose ADB or the emulator through ngrok")
    require("adb tcpip" not in lowered, "ADB must remain local to the Kaggle worker")
    require("accessibilitynodeinfo" in lowered, "kernel must identify structured node-tree perception")
    require("/dev/kvm" in source, "kernel must gate emulator execution on KVM availability")
    require("enable_gpu" not in source, "accelerator selection belongs only in metadata")

    with tempfile.TemporaryDirectory(prefix="fingertip-kaggle-plan-") as work:
        completed = subprocess.run(
            [
                sys.executable,
                str(code_file),
                "--plan",
                "--source-dir",
                str(ROOT),
            ],
            env={"PATH": "/usr/bin:/bin", "FINGERTIP_WORK_DIR": work},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
            check=False,
        )
    require(completed.returncode == 0, f"kernel plan failed:\n{completed.stdout}")
    require("plan valid" in completed.stdout, f"kernel plan did not confirm validity:\n{completed.stdout}")
    require("screenshots/OCR are not used" in completed.stdout, "plan must state its perception path")

    print("Kaggle kernel validation: clean")
    print(f"  id:       {metadata['id']}")
    print(f"  code:     {metadata['code_file']}")
    print("  compute:  CPU (no GPU)")
    print("  device:   runs only with KVM; otherwise reports a truthful skip")
    print("  vision:   AccessibilityNodeInfo tree; no screenshots or OCR")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
