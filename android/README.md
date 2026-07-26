# Android module

> **Status: written but never compiled.** This module was authored in an
> environment with no Android SDK, so it has not been through a compiler or run
> on a device. Treat every file here as a reviewed design draft, not working
> code. `:core` is a different matter — it is fully tested on the JVM.

## Wiring it up

1. Add `include(":android")` to `settings.gradle.kts`.
2. Add `id("com.android.application") version "8.7.3" apply false` to the root
   `build.gradle.kts` plugins block.
3. Add `kotlin("android") version "2.1.0" apply false` alongside it.
4. Point `ANDROID_HOME` at an SDK with platform 35 installed.
5. `./gradlew :android:assembleDebug`

Expect to fix compile errors on the first pass — API-level details around
`AccessibilityNodeInfo.AccessibilityAction` and `TextToSpeech` overrides are the
likely culprits.

## What each file does

| File | Role |
|---|---|
| `AccessibilityDevice.kt` | Implements the `Device` port from `:core`. The only Android-aware logic in the project. |
| `Gestures.kt` | Synthetic tap/swipe fallback via `dispatchGesture`, used only when accessibility actions fail. |
| `SkillRunner.kt` | Owns the background thread and barge-in cancellation. |
| `TtsSpeaker.kt` | Interruptible, queued speech at a rate screen reader users expect. |
| `FingertipAccessibilityService.kt` | Service entry point and TalkBack coexistence rules. |

## Things that will bite you

**Threading.** `AccessibilityDevice.snapshot()` and every action must run off the
main thread. `SkillInterpreter` blocks while polling for screens; on the main
thread that freezes the UI and the system kills the service. `SkillRunner` owns
that thread — never call the device directly from a service callback.

**TalkBack.** Users run TalkBack for everything outside this app. The service
config deliberately omits `canRequestTouchExplorationMode`,
`flagRequestTouchExplorationMode` and `flagRequestFilterKeyEvents`. Adding any of
them makes the phone unusable. This is a reader and actor, not a navigation
layer.

**`FLAG_SECURE`.** Banking and DRM apps block screen capture. The node tree is
usually still readable, which is exactly why the architecture treats the tree as
primary and screenshots as fallback. `isSecureWindow()` is currently a stub
returning `false` — Android exposes no direct query, so implement it as a probe
of `takeScreenshot` and keep it a hint, never a gate on functionality.

**Node recycling.** `AccessibilityNodeInfo.recycle()` is a no-op from API 33 and
deprecated. `AccessibilityDevice` holds a handle map for the duration of one
skill step; on older API levels a long-running skill may want to re-snapshot
rather than hold references across screen transitions.

**Play Store review.** `isAccessibilityTool="true"` is set and the service
description in `strings.xml` is the prominent disclosure Play requires. It names
what is read, what leaves the device, and what is stripped first. Keep it
specific — generic boilerplate is a common rejection reason. `QUERY_ALL_PACKAGES`
also needs a declared justification; if review pushes back, replace it with a
`<queries>` element generated from the skill pack.

## Not built yet

- The voice input layer. Nothing currently calls `onSpokenRequest`; wire it to
  speech recognition, a hardware button, or a quick settings tile.
- `SkillRunner.escalation` is a no-op stub. That is the hook where a reasoning
  agent explores an unknown task and authors a new skill.
- Earcons. `SkillInterpreter` emits `onProgress`; nothing plays a sound for it.
