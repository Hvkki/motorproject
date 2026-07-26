# Fingertip

A voice-driven Android agent that operates apps for blind users — it reads the
screen and taps, types and swipes on request.

This repository is a **prototype of the architecture**, not a shippable app. The
core is complete and tested; the Android layer is written but has never been
compiled. See [Status](#status).

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
core/     Pure Kotlin/JVM. All logic. No Android imports. 97 tests.
android/  Thin AccessibilityService adapter over the Device port. UNCOMPILED.
skills/   The skill pack. One JSON file per task.
tools/    privacy_guard.sh
```

The split is what makes the core testable without an emulator — and it is what
will make scheduled regression runs possible, which matters because third-party
app UIs change silently and rot a skill library.

## Build

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew :core:test
tools/privacy_guard.sh
```

No Android SDK required: `:android` is deliberately excluded from
`settings.gradle.kts`. See [android/README.md](android/README.md) to wire it up.

> Gradle 8.14 cannot run on JDK 25 and fails with a bare version string as the
> entire error message. Use JDK 17 or 21.

## Status

**Done and verified — 97 passing tests:**

- Screen model, selector engine, node-tree serializer
- Redaction with the type-level egress guarantee
- Skill format, deterministic interpreter, utterance matching
- `SkillValidator` + `SkillPackTest`: the merge gate for machine-written skills
- Blind-first speech formatting
- `FakeDevice` with a virtual clock — skills replay against recorded screens on a
  plain JVM

**Written but never compiled** (no Android SDK in the authoring environment):

- `AccessibilityDevice`, gestures, TTS, the service itself, manifest and config

**Not built:**

- Voice input. Nothing calls `onSpokenRequest` yet.
- The escalation hook. `SkillRunner.escalation` is a stub — this is where the
  reasoning agent would author new skills.
- Earcons for tool activity.
- `isSecureWindow()` is a stub returning `false`.
