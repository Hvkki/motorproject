---
inclusion: always
---

# Fingertip engineering invariants

Fingertip reads the screen and taps for blind users. Two consequences drive
everything below: the output is **heard, not seen**, and the input is **the most
sensitive data on the phone**.

## Speech output

- Never narrate data structures. No roles, handles, indices, selector syntax or
  class names in anything spoken. `SpeechFormatter` exists for this; use it
  rather than string-building at call sites.
- Summarise, do not enumerate. "Added a null check to the login handler, 3
  lines" beats reading a diff. Detail is available on request.
- Lead with orientation: app, then screen, then contents. Someone who cannot see
  needs to know *where they are* before *what is there*.
- Every failure must state what went wrong **and** offer a next action. A dead
  end with no way forward is the worst outcome.
- Silence is ambiguous. A skill that succeeds without speaking is
  indistinguishable from one that crashed — always confirm.
- Assume a fast listener. Screen reader users listen far quicker than sighted
  people expect; default TTS rate feels patronising.

## Privacy

- Screen content is raw **on device** and redacted **at egress**. Reading the
  user their own balance is the product; sending it to a server is not.
- Everything leaving the device passes through `Redactor` first. The
  `RedactedSnapshot` constructor is `internal` to enforce this at compile time —
  do not widen it.
- Never log screen content. `tools/privacy_guard.sh` blocks all logging in
  production sources; use the documented allow comment only with justification.
- Tell the user when values were hidden. Silent degradation is disorienting.

## TalkBack coexistence

Users depend on TalkBack for everything outside this app. It must keep winning.

- Never add `canRequestTouchExplorationMode`,
  `flagRequestTouchExplorationMode`, or `flagRequestFilterKeyEvents`.
- Fingertip is a reader and actor, not a navigation layer. It must not intercept
  the user's own gestures.
- Barge-in is mandatory: when the user speaks, stop talking immediately and
  abandon the running task.

## Architecture

- All logic lives in `:core` as pure Kotlin with no Android imports, so it is
  testable on a JVM. `:android` is a thin adapter over the `Device` port. If
  logic starts accumulating in the adapter, move it to core.
- Prefer the accessibility node tree over screenshots. It is cheaper, faster and
  more reliable. Screenshots are the fallback for unlabelled and canvas UI.
- Address nodes by handle, not coordinates. Coordinate taps break under font
  scaling, split screen, and different screen geometry.
- Expensive reasoning produces **skills**, not taps. Escalate once per task
  type, emit a deterministic skill, replay it free forever after.

## Adding or changing a skill

- One JSON file per skill in `skills/`, filename matching the skill id.
- Always `waitFor` before acting on a freshly launched or freshly navigated
  screen. Tapping straight after `launch` is the classic flaky pattern.
- Prefer structural selectors (role, index) over user-visible text where
  localisation matters; prefer text where layout is volatile. Note the tradeoff
  in `notes`.
- Mark any skill not confirmed on a real device as such in `notes`.
- `SkillValidator` errors block a merge; warnings need a human decision.

## Commands

```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))  # needs JDK 17-21
./gradlew :core:test          # 97 tests, no Android SDK required
tools/privacy_guard.sh        # must print "clean"
tools/privacy_guard.sh --self-test
```

Gradle 8.14 cannot run on JDK 25; it fails with a bare version number as the
error message. Use JDK 17 or 21.

`:android` is intentionally excluded from `settings.gradle.kts` so the suite runs
without an Android SDK. See `android/README.md` to build the app.
