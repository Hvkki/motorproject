# Android module

> **Status: compiles and packages. Never run on a device.**
>
> `./gradlew :android:assembleDebug` produces a 1.2 MB debug APK and
> `:android:lintDebug` passes with zero errors and zero warnings, against
> compileSdk 35 / AGP 8.7.3 / Kotlin 2.1.0 / JDK 17.
>
> What that does *not* prove: no line of this has executed on real hardware. The
> accessibility node-tree assumptions, gesture dispatch, and TalkBack coexistence
> are all unverified in practice. Compiling is a floor, not a ceiling.

## Building

The module is included automatically when an SDK is visible — via `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`. Without one,
`settings.gradle.kts` skips it so the core suite still runs on a bare JDK.

```bash
export ANDROID_HOME=/path/to/android-sdk   # needs platforms;android-35, build-tools;35.0.0
export JAVA_HOME=/path/to/jdk17            # Gradle 8.14 will not run on JDK 25
./gradlew :android:assembleDebug :android:lintDebug
```

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
specific — generic boilerplate is a common rejection reason.

**Package visibility limits the flywheel.** An earlier draft used
`QUERY_ALL_PACKAGES`; Lint rejects it and Play grants it to almost nobody, so the
manifest now declares an explicit `<queries>` list. The consequence is
structural and worth understanding before betting on shareable skill packs: that
list is fixed at build time, so **a skill pack delivered over the air cannot add
support for an app that is not already declared.** New app support needs an app
release. `SkillPackTest` fails the build if a skill targets an undeclared
package, so the two cannot drift, but the release coupling is real.

## Not built yet

- The voice input layer. Nothing currently calls `onSpokenRequest`; wire it to
  speech recognition, a hardware button, or a quick settings tile.
- `SkillRunner.escalation` is a no-op stub. That is the hook where a reasoning
  agent explores an unknown task and authors a new skill.
- Earcons. `SkillInterpreter` emits `onProgress`; nothing plays a sound for it.
