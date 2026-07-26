rootProject.name = "fingertip"

// Only the pure-JVM core is part of the Gradle build.
//
// The Android adapter (android/) is deliberately NOT included here: it needs the
// Android SDK + AGP, which are not available in every environment. Keeping it out
// means `./gradlew test` works anywhere with just a JDK, which is what makes the
// core suite runnable in CI and in scheduled evals.
include(":core")
