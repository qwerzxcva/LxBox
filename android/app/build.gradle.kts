import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.leadaxe.aibox"
    compileSdk = 36

    // Release signing: sourced from android/key.properties when present
    // (CI writes it from the ANDROID_KEYSTORE_* repo secrets, matching the
    // Flutter-side setup), otherwise release builds fall back to the debug
    // keystore so a plain `./gradlew assembleRelease` still produces an
    // installable APK on a dev machine.
    val keystorePropsFile = rootProject.file("key.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    val hasUploadKey = keystoreProps.getProperty("storeFile") != null ||
        System.getenv("ANDROID_KEYSTORE_BASE64") != null

    defaultConfig {
        applicationId = "com.leadaxe.aibox"
        minSdk = 26
        targetSdk = 36
        // versionCode grows with the commit count so every build strictly
        // increases; versionName follows the release tag (v0.1 -> 0.1)
        // when CI injects RELEASE_TAG.
        versionCode = runCatching {
            providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
                .standardOutput.asText.get().trim().toInt() * 10
        }.getOrDefault(10)
        // Accept only v-prefixed versions (v0.1, v1.2.3) so a branch-name
        // ref never becomes the version string.
        versionName = System.getenv("RELEASE_TAG")
            ?.takeIf { Regex("^v\\d+(\\.\\d+)*$").matches(it) }
            ?.removePrefix("v")
            ?: "0.1"

        // Keep only the locales the app actually ships; drops the dozens of
        // translations bundled by AndroidX/Compose libraries.
        resourceConfigurations += listOf("en", "zh", "zh-rCN")
    }

    // arm64-v8a is the only shipped target: the user's devices are all
    // ARMv8, and a single-ABI core .so keeps the APK at ~21 MB (an
    // all-ABI APK was 83 MB). The CI workflow builds the matching
    // arm64-only libbox AAR from source.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasUploadKey && keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.create("upload") {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            } else {
                // Debug-sign the release build so it installs; CI sets the
                // real key via key.properties before assembleRelease runs.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/**",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(files("libs/libbox.aar"))
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
}
