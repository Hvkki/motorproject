plugins {
    id("com.android.application")
    kotlin("android")
}

/**
 * Stages the skill pack into an `assets/skills/` directory.
 *
 * `assets.srcDirs` flattens each source directory into the assets root, so
 * pointing it straight at the top-level `skills` directory would land the JSON
 * files directly in the assets root, while `SkillPack.load` looks for them under
 * an `assets/skills` subdirectory. Copying into a nested directory keeps the
 * pack at the repository root — where it is reviewed and diffed — without
 * duplicating it into the module.
 *
 * Note: avoid writing a slash-star glob in Kotlin comments. Kotlin block
 * comments nest, so it opens a nested comment and silently swallows the rest of
 * the file.
 */
val syncSkillPack by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("skills"))
    into(layout.buildDirectory.dir("generated/skillAssets/skills"))
    include("**/*.json")
}

android {
    namespace = "dev.fingertip.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.fingertip"
        // dispatchGesture, used by the gesture fallback, needs API 24+.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
            assets.srcDirs(
                "src/main/assets",
                layout.buildDirectory.dir("generated/skillAssets"),
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric runs the real Android framework on the JVM, which is the
            // only way to exercise AccessibilityNodeInfo without KVM and an
            // emulator. Resources are required for it to build a package context.
            isIncludeAndroidResources = true
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") {
    dependsOn(syncSkillPack)
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Encrypted-at-rest storage for the model API key. With no backend the key
    // lives on the device, so it must not sit in a plain preferences file.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Fast tier: real Android framework classes on the JVM, seconds per run.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")

    // Slow tier: real device/emulator, requires KVM. Compiled here, run elsewhere.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
