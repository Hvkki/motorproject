# Fingertip

A voice-driven Android agent that operates apps for blind users — it reads the
screen and taps, types and swipes on request.

This repository is a **prototype of the architecture**, not a shippable app. The
core is fully tested, the Android APK and instrumentation APK compile, and the
structured node-tree device tests are ready to run where KVM is available. See
[Status](#status).

## The idea

The obvious design is a loop: screenshot the screen, ask a model what to tap, tap
it, repeat. It works in a demo and fails as a product — every action costs a
network round trip, so a user waits seconds to dismiss a dialog, pays per tap,
and gets nothing without signal.

Fingertip splits the problem in two:

| | Fast tier | Slow tier |
|---|---|---|
| Handles | Known tasks (~95%) | Novel tasks |
| Mechanism | Replays a **skill** deterministically | A reasoning agent explores and **writes a skill** |
| Cost | Zero | Once per task *type* |
| Latency | Local, immediate | Seconds, occasionally |

The reasoning agent's output is **code, not clicks**. A
[skill](skills/whatsapp.read_last_message.json) is reviewable JSON: steps,
selectors, and what to say. Once written, "read my last message" replays
instantly, offline, identically every time.

Because skills are files, they live in git. One user getting stuck on some
airline's checkout produces a skill that fixes that app for everyone — reviewed
in a pull request, gated by CI, shipped as a pack. The library is the moat, not
the prompt.

## Privacy model

An agent that can read every screen needs a defensible answer about where that
data goes. Fingertip's is a single boundary:

**Raw on device. Redacted at egress.**

Reading your own balance aloud is the entire point, so local skill execution sees
everything. Anything crossing to a model, a log, or a crash report goes through
`Redactor` first, which strips password fields, card numbers, IBANs and one-time
codes.

This is enforced structurally, not by convention:

- `RedactedSnapshot` has an `internal` constructor, so no code outside the
  privacy package can fabricate one.
- `ScreenSerializer` — the only thing that turns a screen into text for a model —
  accepts `RedactedSnapshot` and nothing else. Serialising raw screen content is
  a compile error.
- `tools/privacy_guard.sh` blocks all logging in production sources, and
  self-tests to prove the check still fires.

## Layout

```
core/     Pure Kotlin/JVM. All logic. No Android imports. 98 tests.
android/  AccessibilityService adapter. 18 Robolectric JVM tests + device tests.
kaggle/   Private script-kernel metadata and cloud validation runner.
skills/   The skill pack. One JSON file per task.
tools/    Privacy guard and Kaggle metadata/dry-run validator.
```

The split is what makes the core testable without an emulator — and it is what
will make scheduled regression runs possible, which matters because third-party
app UIs change silently and rot a skill library.

## Build

Three test tiers, fastest first:

| Tier | Command | Needs | Time |
|---|---|---|---|
| Core logic | `./gradlew :core:test` | JDK only | seconds |
| Android framework | `./gradlew :android:testDebugUnitTest` | Android SDK | ~30s |
| Real device | `./gradlew :android:connectedDebugAndroidTest` | emulator + **KVM** | minutes |

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew :core:test          # no Android SDK required
tools/privacy_guard.sh
```

`:android` is included automatically when an SDK is visible (`ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`), and skipped when it is
not — so the core suite runs on a bare JDK:

```bash
export ANDROID_HOME=/path/to/android-sdk   # platforms;android-35, build-tools;35.0.0
./gradlew :android:testDebugUnitTest :android:assembleDebug :android:lintDebug
```

> The emulator requires **KVM**, not a GPU. Nested virtualization is a CPU
> feature; a GPU accelerates neither Gradle nor QEMU's CPU emulation. Without
> `/dev/kvm`, a full Android boot runs under software emulation and is far too
> slow to be practical — which is exactly why the Robolectric tier exists.

> Gradle 8.14 cannot run on JDK 25 and fails with a bare version string as the
> entire error message. Use JDK 17 or 21.

## Kaggle validation

`kaggle/fingertip_kaggle.py` is a private Kaggle script kernel that provisions
JDK 17 and Android API 35, then runs the privacy guard, all core tests, APK and
instrumentation-APK builds, and Android Lint. It publishes the APK, reports and a
machine-readable summary under `/kaggle/working/fingertip-artifacts`.

The runner uses the **accessibility node tree**, not screenshot-only automation:
`RealSettingsNodeTreeTest` consumes Android's real `AccessibilityNodeInfo` tree,
serializes the redacted compact representation, and performs a semantic click by
walking from a label to its clickable ancestor. Screenshots and OCR are not used
by these tests.

A GPU is deliberately disabled because it does not accelerate Gradle or QEMU CPU
emulation. Instrumentation runs only when the Kaggle worker exposes `/dev/kvm`;
otherwise the kernel records an honest skip while still completing every build,
JVM test and Lint check. A slow software-emulator attempt is opt-in via
`FINGERTIP_FORCE_SOFTWARE_EMULATOR=1`. ADB remains local to the worker and is
never exposed through ngrok.

Validate the kernel without Kaggle credentials:

```bash
python3 tools/validate_kaggle_kernel.py
python3 kaggle/fingertip_kaggle.py --plan --source-dir .
```

To publish it, first configure the Kaggle CLI token, then run:

```bash
pip install kaggle
kaggle kernels push -p kaggle --timeout 7200
kaggle kernels status hvkki/fingertip-android-validation
kaggle kernels output hvkki/fingertip-android-validation -p kaggle-output
```

If the Kaggle username is not `hvkki`, change the `id` owner in
`kaggle/kernel-metadata.json` before pushing. See the official
[Kaggle kernels CLI documentation](https://github.com/Kaggle/kaggle-cli/blob/main/docs/kernels.md).

## Status

**Done and verified — 98 passing tests:**

- Screen model, selector engine, node-tree serializer
- Redaction with the type-level egress guarantee
- Skill format, deterministic interpreter, utterance matching
- `SkillValidator` + `SkillPackTest`: the merge gate for machine-written skills
- Blind-first speech formatting
- `FakeDevice` with a virtual clock — skills replay against recorded screens on a
  plain JVM

**Verified against the real Android framework — 18 Robolectric tests:**

- `AndroidNodeTree`: traversal, semantic role mapping from real `className`
  strings, node and depth budgets, and the handle-to-live-node invariant that
  makes addressing a node by handle safe.
- `AndroidNodeActions`: the ancestor walk that clicks a row when a skill selected
  its label, skipping disabled ancestors and refusing to climb without bound.
- End-to-end privacy: a real `AccessibilityNodeInfo` password field, card number
  and one-time code are all redacted before serialisation.

Robolectric runs these on the JVM in about 30 seconds with no emulator and no
KVM, which is what makes them usable as a pre-commit gate.

**Compiles, but not yet run on real hardware:**

- `AccessibilityDevice`, gestures, TTS, the service, manifest and config build
  into a 1.2 MB debug APK; Lint passes with zero errors and warnings.
- Robolectric uses shadow implementations. It proves the traversal and click
  logic is correct against the framework's API contract, **not** that a real
  device behaves identically. Real gesture dispatch, genuine third-party app
  layouts, and TalkBack coexistence remain unverified.
- `RealSettingsNodeTreeTest` covers those against Android Settings and runs in
  the `instrumentation` CI job, where GitHub's Linux runners provide KVM.

**Not built:**

- Voice input. Nothing calls `onSpokenRequest` yet.
- The escalation hook. `SkillRunner.escalation` is a stub — this is where the
  reasoning agent would author new skills.
- Earcons for tool activity.
- `isSecureWindow()` is a stub returning `false`.
