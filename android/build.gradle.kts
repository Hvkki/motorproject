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
}
