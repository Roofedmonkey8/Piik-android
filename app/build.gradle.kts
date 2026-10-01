plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.piikandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.piikandroid"
        minSdk = 29 // Android 10: playback-audio capture and modern MediaCodec keys
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Piik's Go binaries are built for 64-bit ARM (and x86_64 for emulators).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        // CI and local builds sign with the debug key unless a keystore is provided.
        create("release") {
            val store = System.getenv("PIIK_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("PIIK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PIIK_KEY_ALIAS")
                keyPassword = System.getenv("PIIK_KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The Go executables ship as lib*.so so Android installs them where
        // apps may execute files; they must be extracted, not mapped from the APK.
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
