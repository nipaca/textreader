plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Version comes from version.txt in the repo root (e.g. 1.2.3) ----
val appVersion: String = rootProject.file("version.txt").readText().trim()
val versionParts: List<Int> = appVersion.split(".").map { it.toInt() }
require(versionParts.size == 3) { "version.txt must look like 1.0.0" }
val appVersionCode: Int = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

// ---- Optional release signing (set up later through GitHub secrets) ----
val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.textreader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.textreader.app"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion

        // Samsung Galaxy S25+ is 64-bit ARM; leaving other CPU types out keeps the app smaller.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (signingKeystorePath != null) {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Code shrinking stays off: the voice engine talks to its native code by field names.
            isMinifyEnabled = false
            signingConfig = if (signingKeystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                // Until signing is set up, builds are signed with a temporary key.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Big model files are read directly from the APK, so keep them uncompressed.
        noCompress += listOf("onnx", "bin", "traineddata")
    }

    lint {
        // Don't let style warnings stop a release build.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
        }
        resources {
            excludes += listOf(
                "META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*", "META-INF/NOTICE*",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // The voice engine library may be built with a newer Kotlin than ours.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Offline neural voices (Kokoro) — downloaded into app/libs by the GitHub workflow.
    implementation(files("libs/sherpa-onnx.aar"))

    // Offline PDF text extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Offline OCR for scanned pages and image-only screens
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
}
