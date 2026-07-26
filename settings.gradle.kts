pluginManagement {
    repositories {
        // AGP is published to Google's Maven, not the plugin portal.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "fingertip"

include(":core")

// The Android app module. Requires an Android SDK with platform 35 (set
// ANDROID_HOME, or sdk.dir in local.properties).
//
// It is guarded so the core test suite still runs on a machine with only a JDK:
// that is what keeps `:core:test` usable in CI and in scheduled skill-regression
// runs. If no SDK is visible, the build silently skips the app module.
val androidSdk: String? = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter('=')

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":android")
} else {
    logger.lifecycle("No Android SDK found; skipping :android. Core tests are unaffected.")
}
