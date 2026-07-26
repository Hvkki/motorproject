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
| Mechanism | `SkillInterpreter` replays a **skill** deterministically | `AgentLoop` reasons over the screen and **writes a skill** |
| Cost | Zero | Once per task *type* |
| Latency | Local, immediate | Seconds, occasionally |
| Model calls | None | One per action |

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
core/     Pure Kotlin/JVM. All logic. No Android imports. 181 tests.
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

## Testing on a real phone

An emulator cannot verify TalkBack coexistence: `google_apis` images ship no
screen reader, and installing Android Accessibility Suite needs an interactive
Play Store sign-in. Use a physical device:

```bash
export ANDROID_HOME=/path/to/android-sdk
tools/device_test.sh              # add --keep-enabled to keep it on afterwards
```

The script appends Fingertip to the enabled-services list rather than replacing
it, and restores the original settings on exit even on failure. Replacing that
list would switch TalkBack off, which on a blind user's phone is a lockout rather
than a test. It writes `device-test-report.txt` containing the result, whether
TalkBack stayed bound, and the redacted Settings node tree the agent actually saw.

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

**Verified on a real Android system — `RealSettingsNodeTreeTest` passing in CI:**

The `instrumentation` job boots a hardware-accelerated API 35 emulator on
GitHub's KVM-enabled Linux runners, installs the app, enables the accessibility
service, and tests against the **real Android Settings app**:

- The genuine Settings `AccessibilityNodeInfo` tree is read and serialised to
  compact redacted text, with no screenshot and no OCR.
- The ancestor walk clicks a *label* and successfully navigates, proving the
  behaviour every skill depends on works against a real system app.
- The handle-to-live-node mapping holds on a real tree, and the node budget
  truncates a genuinely wide one.

So the `AccessibilityNodeInfo` assumptions are no longer guesses.

**Still unverified — needs a physical phone:**

- **TalkBack coexistence, the project's biggest design risk.** Emulator
  `google_apis` images do not ship TalkBack; it comes from Android Accessibility
  Suite via the Play Store, whose install needs an interactive Google sign-in
  that CI cannot reliably perform. Run `tools/device_test.sh` on a real device.
- Real gesture dispatch (`dispatchGesture`), which only matters where
  accessibility actions fail and the coordinate fallback engages.
- Third-party app layouts. Settings on AOSP is not Settings on One UI, and the
  two example skills use guessed selectors marked `NOT DEVICE-VERIFIED`.
- Speech output. `TtsSpeaker` has never spoken.

## No backend

The app talks **directly** to whichever model provider the user chooses. There is
no server of ours in the path: nothing to run, pay for, scale or patch, and nothing
in the middle that could retain screen content.

| | |
|---|---|
| Providers | Anthropic, Gemini, or any OpenAI-compatible endpoint |
| Transport | `HttpsURLConnection`, zero third-party dependencies |
| Key storage | `EncryptedSharedPreferences`, Android Keystore-backed |
| Cleartext | Refused, in both the network config and the transport |

The trade-off is honest: with no backend, the API key lives on the device. Keystore
encryption defeats extraction from a backup or a plain preferences file. It does not
defeat a rooted phone or malware running as this app — no client-side storage can.
The key is the user's own and revocable at the provider.

Kiro cannot serve as the on-device brain, and that is a property of Kiro rather than
a shortcut taken here: `kiro-cli` does not run on Android and `KIRO_API_KEY`
authenticates a local CLI process, not an HTTPS endpoint a phone can call.
`KiroCliPlanner` is retained for desktop and CI use, where it is verified working
end to end.

### Offline

Replaying a skill needs no network. Once a task has been learned it runs with no
connection, and the reasoning tier fails with a clear spoken message instead of
hanging. `INTERNET` is unused until a key is configured.

**The reasoning tier — built and tested, needs a key configured:**

`AgentLoop` pursues goals nobody wrote a skill for: observe the screen, decide one
action, perform it, observe again. "Search Google for the weather" works with no
recipe. Its action vocabulary *is* the skill format, so a successful run is
compiled by `SkillRecorder` into a replayable skill — the next time the same
request arrives it takes the fast path with **zero planner calls**. There is a
test that does exactly that end to end.

Safety is in the loop, not left to the model:

- **Irreversible actions are confirmed first.** A sighted user can see "Send £400"
  under their thumb and stop; someone relying on a screen reader is trusting the
  agent's description, so `RiskPolicy` gates committing taps and **denies by
  default** when no prompt is wired up.
- **Loop detection.** Repeating an action on an unchanged screen aborts, because
  tapping a dead button forever reads as unexplained silence.
- **Budgets** on planner calls, wall clock, and consecutive failures.
- **Failures are fed back** to the planner so it can route around them.
- The planner receives only a `RedactedSnapshot`, so it is structurally incapable
  of seeing a password or one-time code.

`Planner` is the single seam a language model plugs into. Everything around it is
deterministic, which is why all of the above is tested with no API key.

**Not built:**

- **Voice input.** Nothing calls `onSpokenRequest`, so today nothing can trigger
  either tier from speech. This is the single remaining blocker to the app being
  usable, and it is why `confirm` still denies everything.
- Persisting learned skills. A recorded skill currently lives only as long as the
  service does — deliberately, since storing unreviewed automation that can tap
  "Pay" needs a review-and-revoke design first.
- Earcons for tool activity.
- `isSecureWindow()` is a stub returning `false`.
