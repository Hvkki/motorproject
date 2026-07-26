plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    kotlin("android") version "2.1.0" apply false
    id("com.android.application") version "8.7.3" apply false
}

subprojects {
    repositories {
        // google() first: the Android artifacts are only there.
        google()
        mavenCentral()
    }
}
