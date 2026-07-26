plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    // 11 keeps this module consumable by the Android app module unchanged.
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        // Surface test stdout only when running the opt-in live Kiro tests, whose
        // transcript is the whole point of running them. Keeps CI output clean.
        showStandardStreams = !System.getenv("KIRO_API_KEY").isNullOrBlank()
    }
}
