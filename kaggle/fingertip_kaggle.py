#!/usr/bin/env python3
"""Build and validate Fingertip on Kaggle.

The fast path always runs:
  * privacy guard and its self-test
  * 98 pure-JVM core tests
  * Android debug APK + instrumentation APK compilation
  * Android Lint

The device path runs only when /dev/kvm is available (or software emulation is
explicitly forced). It boots Android, enables Fingertip's real accessibility
service, and executes tests against Android Settings through the structured
AccessibilityNodeInfo tree. It never uses screenshots or OCR.

Kaggle executes this file as a script kernel. It deliberately needs no Kaggle or
GitHub token: the source repository is public and all output goes to
/kaggle/working/fingertip-artifacts.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Iterable, Mapping, Sequence

REPOSITORY_URL = "https://github.com/Hvkki/motorproject.git"
DEFAULT_BRANCH = "main"
COMMAND_LINE_TOOLS_BUILD = "14742923"
ANDROID_API = "35"
BUILD_TOOLS = "35.0.0"
SERVICE_COMPONENT = "dev.fingertip/dev.fingertip.android.FingertipAccessibilityService"
TEST_RUNNER = "dev.fingertip.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.fingertip.android.RealSettingsNodeTreeTest"


def section(title: str) -> None:
    print(f"\n{'=' * 78}\n{title}\n{'=' * 78}", flush=True)


def command_text(command: Sequence[str]) -> str:
    """Readable command text without shell interpolation or environment values."""
    return " ".join(str(part) for part in command)


def run(
    command: Sequence[str],
    *,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    check: bool = True,
    capture: bool = False,
    input_text: str | None = None,
    timeout: int | None = None,
) -> subprocess.CompletedProcess[str]:
    print(f"+ {command_text(command)}", flush=True)
    return subprocess.run(
        [str(part) for part in command],
        cwd=str(cwd) if cwd else None,
        env=dict(env) if env else None,
        check=check,
        text=True,
        input=input_text,
        timeout=timeout,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )


def run_logged(
    command: Sequence[str],
    log_file: Path,
    *,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    input_text: str | None = None,
    timeout: int | None = None,
) -> str:
    """Run a command, stream output to the notebook, and retain a build artifact."""
    print(f"+ {command_text(command)}", flush=True)
    log_file.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(
        [str(part) for part in command],
        cwd=str(cwd) if cwd else None,
        env=dict(env) if env else None,
        text=True,
        stdin=subprocess.PIPE if input_text is not None else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if input_text is not None and process.stdin is not None:
        process.stdin.write(input_text)
        process.stdin.close()

    started = time.monotonic()
    chunks: list[str] = []
    with log_file.open("w", encoding="utf-8") as log:
        assert process.stdout is not None
        while True:
            if timeout is not None and time.monotonic() - started > timeout:
                process.kill()
                raise TimeoutError(f"Timed out after {timeout}s: {command_text(command)}")
            line = process.stdout.readline()
            if line:
                print(line, end="", flush=True)
                log.write(line)
                chunks.append(line)
                continue
            if process.poll() is not None:
                break
            time.sleep(0.05)

    if process.returncode != 0:
        raise subprocess.CalledProcessError(process.returncode, command)
    return "".join(chunks)


def download(url: str, destination: Path) -> None:
    if destination.is_file() and destination.stat().st_size > 1_000_000:
        print(f"Using cached {destination.name} ({destination.stat().st_size:,} bytes)")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    print(f"Downloading {url}", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "Fingertip-Kaggle/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as out:
        shutil.copyfileobj(response, out)
    temporary.replace(destination)


def safe_extract_tar(archive: Path, destination: Path) -> None:
    """Extract a trusted JDK archive while preventing path traversal."""
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            target = (destination / member.name).resolve()
            if target != root and root not in target.parents:
                raise RuntimeError(f"Unsafe archive path: {member.name}")
        tar.extractall(destination)


def ensure_jdk(work: Path) -> Path:
    configured = os.environ.get("FINGERTIP_JAVA_HOME")
    if configured and (Path(configured) / "bin/java").is_file():
        return Path(configured)

    existing = shutil.which("java")
    if existing:
        version = run([existing, "-version"], capture=True, check=False).stdout or ""
        match = re.search(r'version "(\d+)', version)
        if match and 17 <= int(match.group(1)) <= 24:
            java_home = Path(existing).resolve().parent.parent
            print(f"Using existing JDK {match.group(1)} at {java_home}")
            return java_home

    install_root = work / ".toolchain/jdk17"
    java = install_root / "bin/java"
    if java.is_file():
        return install_root

    archive = work / ".cache/temurin17.tar.gz"
    download(
        "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/"
        "hotspot/normal/eclipse",
        archive,
    )
    temporary = install_root.with_name("jdk17.extracting")
    shutil.rmtree(temporary, ignore_errors=True)
    safe_extract_tar(archive, temporary)
    children = [entry for entry in temporary.iterdir() if entry.is_dir()]
    if len(children) != 1:
        raise RuntimeError(f"Expected one JDK directory, found {children}")
    install_root.parent.mkdir(parents=True, exist_ok=True)
    children[0].rename(install_root)
    temporary.rmdir()
    return install_root


def ensure_command_line_tools(work: Path, java_home: Path) -> Path:
    configured = os.environ.get("FINGERTIP_ANDROID_HOME") or os.environ.get("ANDROID_HOME")
    if configured and (Path(configured) / "cmdline-tools/latest/bin/sdkmanager").is_file():
        return Path(configured)

    sdk = work / ".toolchain/android-sdk"
    sdkmanager = sdk / "cmdline-tools/latest/bin/sdkmanager"
    if sdkmanager.is_file():
        return sdk

    archive = work / f".cache/commandlinetools-linux-{COMMAND_LINE_TOOLS_BUILD}.zip"
    download(
        "https://dl.google.com/android/repository/"
        f"commandlinetools-linux-{COMMAND_LINE_TOOLS_BUILD}_latest.zip",
        archive,
    )
    extracting = work / ".toolchain/cmdline-tools.extracting"
    shutil.rmtree(extracting, ignore_errors=True)
    extracting.mkdir(parents=True)
    with zipfile.ZipFile(archive) as zipped:
        zipped.extractall(extracting)
    target = sdk / "cmdline-tools/latest"
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(target, ignore_errors=True)
    shutil.move(str(extracting / "cmdline-tools"), str(target))
    shutil.rmtree(extracting)
    return sdk


def android_environment(java_home: Path, sdk: Path) -> dict[str, str]:
    env = os.environ.copy()
    env.update(
        {
            "JAVA_HOME": str(java_home),
            "ANDROID_HOME": str(sdk),
            "ANDROID_SDK_ROOT": str(sdk),
            "PATH": os.pathsep.join(
                [
                    str(java_home / "bin"),
                    str(sdk / "platform-tools"),
                    str(sdk / "emulator"),
                    env.get("PATH", ""),
                ]
            ),
        }
    )
    return env


def install_sdk_packages(sdk: Path, env: Mapping[str, str], packages: Iterable[str], log: Path) -> None:
    sdkmanager = sdk / "cmdline-tools/latest/bin/sdkmanager"
    requested = list(packages)
    run_logged(
        [sdkmanager, f"--sdk_root={sdk}", "--install", *requested],
        log,
        env=env,
        input_text="y\n" * 100,
        timeout=1_200,
    )


def acquire_source(work: Path, source_override: str | None) -> Path:
    if source_override:
        source = Path(source_override).resolve()
        if not (source / "settings.gradle.kts").is_file():
            raise FileNotFoundError(f"Not a Fingertip checkout: {source}")
        return source

    source = work / "motorproject"
    if (source / ".git").is_dir():
        run(["git", "fetch", "--depth", "1", "origin", DEFAULT_BRANCH], cwd=source)
        run(["git", "checkout", "--detach", "FETCH_HEAD"], cwd=source)
    else:
        shutil.rmtree(source, ignore_errors=True)
        run(
            [
                "git",
                "clone",
                "--depth",
                "1",
                "--branch",
                os.environ.get("FINGERTIP_BRANCH", DEFAULT_BRANCH),
                REPOSITORY_URL,
                source,
            ]
        )
    return source


def validate_source(source: Path) -> None:
    required = [
        "gradlew",
        "settings.gradle.kts",
        "core/build.gradle.kts",
        "android/build.gradle.kts",
        "android/src/main/kotlin/dev/fingertip/android/AndroidNodeTree.kt",
        "android/src/androidTest/kotlin/dev/fingertip/android/RealSettingsNodeTreeTest.kt",
        "tools/privacy_guard.sh",
    ]
    missing = [path for path in required if not (source / path).is_file()]
    if missing:
        raise FileNotFoundError(f"Checkout is missing required files: {missing}")


def count_tests(results_dir: Path) -> tuple[int, int, int]:
    """Total, failed and skipped tests from a JUnit XML results directory."""
    tests = failures = skipped = 0
    for result in results_dir.glob("*.xml"):
        root = ET.parse(result).getroot()
        tests += int(root.attrib.get("tests", 0))
        failures += int(root.attrib.get("failures", 0)) + int(root.attrib.get("errors", 0))
        skipped += int(root.attrib.get("skipped", 0))
    return tests, failures, skipped


def copy_if_exists(source: Path, destination: Path) -> None:
    if source.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)


def copy_tree_if_exists(source: Path, destination: Path) -> None:
    if source.is_dir():
        shutil.copytree(source, destination, dirs_exist_ok=True)


def build_project(source: Path, env: Mapping[str, str], artifacts: Path) -> dict[str, object]:
    section("Privacy boundary")
    run([source / "tools/privacy_guard.sh", "--self-test"], cwd=source, env=env)
    run([source / "tools/privacy_guard.sh"], cwd=source, env=env)

    # settings.gradle.kts also detects ANDROID_HOME, but local.properties keeps
    # Gradle daemons deterministic across notebook cells and subprocesses.
    (source / "local.properties").write_text(
        f"sdk.dir={env['ANDROID_HOME']}\n",
        encoding="utf-8",
    )

    section("Core tests, Android JVM tests, builds, and Lint")
    output = run_logged(
        [
            source / "gradlew",
            "--no-daemon",
            "--console=plain",
            ":core:test",
            # Robolectric: real Android framework on the JVM, no KVM required.
            ":android:testDebugUnitTest",
            ":android:assembleDebug",
            ":android:assembleDebugAndroidTest",
            ":android:lintDebug",
        ],
        artifacts / "gradle-build.log",
        cwd=source,
        env=env,
        timeout=1_800,
    )
    if "BUILD SUCCESSFUL" not in output:
        raise RuntimeError("Gradle exited without its success marker")

    core_tests, core_failures, core_skipped = count_tests(source / "core/build/test-results/test")
    if core_tests < 98 or core_failures:
        raise RuntimeError(
            f"Unexpected core result: tests={core_tests}, failures={core_failures}, "
            f"skipped={core_skipped}"
        )

    jvm_tests, jvm_failures, jvm_skipped = count_tests(
        source / "android/build/test-results/testDebugUnitTest"
    )
    if jvm_tests < 18 or jvm_failures:
        raise RuntimeError(
            f"Unexpected Android JVM result: tests={jvm_tests}, failures={jvm_failures}, "
            f"skipped={jvm_skipped}"
        )

    copy_tree_if_exists(
        source / "android/build/reports/tests/testDebugUnitTest",
        artifacts / "android-jvm-test-report",
    )
    copy_if_exists(
        source / "android/build/outputs/apk/debug/android-debug.apk",
        artifacts / "fingertip-debug.apk",
    )
    copy_if_exists(
        source / "android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk",
        artifacts / "fingertip-debug-androidTest.apk",
    )
    copy_tree_if_exists(source / "core/build/reports/tests/test", artifacts / "core-test-report")
    copy_tree_if_exists(source / "android/build/reports", artifacts / "android-reports")
    return {
        "core_jvm": {"tests": core_tests, "failures": core_failures, "skipped": core_skipped},
        "android_jvm_robolectric": {
            "tests": jvm_tests,
            "failures": jvm_failures,
            "skipped": jvm_skipped,
        },
    }


def has_kvm() -> bool:
    kvm = Path("/dev/kvm")
    return kvm.exists() and os.access(kvm, os.R_OK | os.W_OK)


def wait_for_android(adb: Path, env: Mapping[str, str], timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        state = run([adb, "get-state"], env=env, capture=True, check=False).stdout or ""
        boot = run(
            [adb, "shell", "getprop", "sys.boot_completed"],
            env=env,
            capture=True,
            check=False,
        ).stdout or ""
        if state.strip() == "device" and boot.strip() == "1":
            return
        time.sleep(5)
    raise TimeoutError(f"Android did not boot within {timeout_seconds}s")


def run_device_tests(
    source: Path,
    sdk: Path,
    env: Mapping[str, str],
    artifacts: Path,
    force_software: bool,
) -> dict[str, object]:
    accelerated = has_kvm()
    if not accelerated and not force_software:
        message = (
            "SKIPPED: /dev/kvm is unavailable. Kaggle cannot accelerate an Android "
            "emulator on this worker. APK, instrumentation APK, Lint and all core "
            "tests still passed. Set FINGERTIP_FORCE_SOFTWARE_EMULATOR=1 only if "
            "you accept a potentially very slow software boot."
        )
        print(message)
        (artifacts / "instrumentation-skipped.txt").write_text(message + "\n", encoding="utf-8")
        return {"status": "skipped", "reason": "kvm_unavailable"}

    if platform.machine() not in {"x86_64", "amd64"}:
        message = f"SKIPPED: emulator image is x86_64, host is {platform.machine()}"
        print(message)
        (artifacts / "instrumentation-skipped.txt").write_text(message + "\n", encoding="utf-8")
        return {"status": "skipped", "reason": "unsupported_host_architecture"}

    section("Android emulator and structured node-tree instrumentation")
    install_sdk_packages(
        sdk,
        env,
        ["emulator", f"system-images;android-{ANDROID_API};google_apis;x86_64"],
        artifacts / "sdk-emulator-install.log",
    )

    avd_home = artifacts.parent / ".android/avd"
    avd_home.mkdir(parents=True, exist_ok=True)
    emulator_env = dict(env)
    emulator_env["ANDROID_AVD_HOME"] = str(avd_home)
    avdmanager = sdk / "cmdline-tools/latest/bin/avdmanager"
    run(
        [
            avdmanager,
            "create",
            "avd",
            "--force",
            "--name",
            "fingertip_kaggle",
            "--package",
            f"system-images;android-{ANDROID_API};google_apis;x86_64",
            "--device",
            "pixel_6",
        ],
        env=emulator_env,
        input_text="no\n",
        timeout=120,
    )

    adb = sdk / "platform-tools/adb"
    run([adb, "start-server"], env=emulator_env)
    emulator = sdk / "emulator/emulator"
    emulator_log = (artifacts / "emulator.log").open("w", encoding="utf-8")
    command = [
        emulator,
        "@fingertip_kaggle",
        "-no-window",
        "-no-audio",
        "-no-boot-anim",
        "-no-snapshot",
        "-wipe-data",
        "-gpu",
        "swiftshader_indirect",
        "-camera-back",
        "none",
        "-camera-front",
        "none",
        "-memory",
        "2048",
        "-cores",
        "2",
        "-accel",
        "on" if accelerated else "off",
    ]
    print(f"+ {command_text(command)}", flush=True)
    emulator_process = subprocess.Popen(
        [str(part) for part in command],
        env=emulator_env,
        stdout=emulator_log,
        stderr=subprocess.STDOUT,
        text=True,
    )

    try:
        wait_for_android(adb, emulator_env, 420 if accelerated else 1_200)
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            run([adb, "shell", "settings", "put", "global", setting, "0"], env=emulator_env)

        app_apk = source / "android/build/outputs/apk/debug/android-debug.apk"
        test_apk = source / "android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk"
        run([adb, "install", "-r", app_apk], env=emulator_env, timeout=180)
        run([adb, "install", "-r", test_apk], env=emulator_env, timeout=180)

        run(
            [
                adb,
                "shell",
                "settings",
                "put",
                "secure",
                "enabled_accessibility_services",
                SERVICE_COMPONENT,
            ],
            env=emulator_env,
        )
        run(
            [adb, "shell", "settings", "put", "secure", "accessibility_enabled", "1"],
            env=emulator_env,
        )
        time.sleep(2)
        accessibility = run(
            [adb, "shell", "dumpsys", "accessibility"],
            env=emulator_env,
            capture=True,
        ).stdout or ""
        (artifacts / "accessibility-service-state.txt").write_text(accessibility, encoding="utf-8")
        if SERVICE_COMPONENT not in accessibility:
            raise RuntimeError("Fingertip service is absent from dumpsys accessibility")

        run([adb, "logcat", "-c"], env=emulator_env, check=False)
        instrumentation = run_logged(
            [
                adb,
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                TEST_CLASS,
                TEST_RUNNER,
            ],
            artifacts / "instrumentation.txt",
            env=emulator_env,
            timeout=300,
        )
        if "OK (3 tests)" not in instrumentation and "OK (3 test" not in instrumentation:
            raise RuntimeError("Instrumentation did not report three passing tests")

        node_tree = run(
            [adb, "exec-out", "run-as", "dev.fingertip", "cat", "files/node-tree.txt"],
            env=emulator_env,
            capture=True,
        ).stdout or ""
        if "app=com.android.settings" not in node_tree:
            raise RuntimeError("Instrumented test produced no redacted Settings node tree")
        (artifacts / "settings-node-tree.txt").write_text(node_tree, encoding="utf-8")
        return {"status": "passed", "accelerated": accelerated, "node_tree_chars": len(node_tree)}
    finally:
        run([adb, "emu", "kill"], env=emulator_env, check=False, timeout=30)
        try:
            emulator_process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            emulator_process.kill()
        emulator_log.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", action="store_true", help="validate inputs and print work without downloads")
    parser.add_argument("--source-dir", help="use an existing checkout instead of cloning GitHub")
    parser.add_argument("--skip-emulator", action="store_true", help="build only, even when KVM is present")
    parser.add_argument(
        "--force-software-emulator",
        action="store_true",
        help="try a slow emulator without KVM (not recommended)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    default_work = Path("/kaggle/working") if Path("/kaggle/working").is_dir() else Path.cwd() / ".kaggle-work"
    work = Path(os.environ.get("FINGERTIP_WORK_DIR", str(default_work))).resolve()
    artifacts = work / "fingertip-artifacts"
    artifacts.mkdir(parents=True, exist_ok=True)

    source = acquire_source(work, args.source_dir or os.environ.get("FINGERTIP_SOURCE_DIR"))
    validate_source(source)

    section("Fingertip Kaggle validation plan")
    print(f"source:       {source}")
    print(f"artifacts:    {artifacts}")
    print(f"host:         {platform.platform()}")
    print(f"KVM:          {'available' if has_kvm() else 'unavailable'}")
    print("perception:   AccessibilityNodeInfo tree (screenshots/OCR are not used)")
    if args.plan:
        print("plan valid: required project and instrumentation files are present")
        return 0

    java_home = ensure_jdk(work)
    sdk = ensure_command_line_tools(work, java_home)
    env = android_environment(java_home, sdk)
    install_sdk_packages(
        sdk,
        env,
        ["platform-tools", f"platforms;android-{ANDROID_API}", f"build-tools;{BUILD_TOOLS}"],
        artifacts / "sdk-build-install.log",
    )

    summary: dict[str, object] = {
        "repository": REPOSITORY_URL,
        "branch": os.environ.get("FINGERTIP_BRANCH", DEFAULT_BRANCH),
        "structured_node_tree_primary": True,
        "screenshots_used": False,
        "tests": build_project(source, env, artifacts),
    }
    if args.skip_emulator or os.environ.get("FINGERTIP_SKIP_EMULATOR") == "1":
        summary["instrumentation"] = {"status": "skipped", "reason": "explicitly_disabled"}
    else:
        force = args.force_software_emulator or os.environ.get("FINGERTIP_FORCE_SOFTWARE_EMULATOR") == "1"
        summary["instrumentation"] = run_device_tests(source, sdk, env, artifacts, force)

    summary["result"] = "passed"
    (artifacts / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    section("Result")
    print(json.dumps(summary, indent=2))
    print(f"Artifacts: {artifacts}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # Kaggle should retain a concise failure artifact.
        print(f"\nFINGERTIP VALIDATION FAILED: {error}", file=sys.stderr, flush=True)
        raise
