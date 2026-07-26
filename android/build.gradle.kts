/*
 * Android app module.
 *
 * NOT part of the root Gradle build: `settings.gradle.kts` includes only `:core`,
 * so the test suite runs anywhere with just a JDK and no Android SDK.
 *
 * To build the app, add this line to settings.gradle.kts:
 *
 *     include(":android")
 *
 * and add the Android Gradle Plugin to the root build's plugins block:
 *
 *     id("com.android.application") version "8.7.3" apply false
 *
 * See android/README.md for the full checklist.
 */

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.fingertip.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.fingertip"
        // 26 covers dispatchGesture, which the gesture fallback depends on.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
            // Skills ship as assets so the pack can be updated independently of code.
            assets.srcDirs("src/main/assets", "../skills")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
