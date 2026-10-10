plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rbpi4b.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rbpi4b.player"
        minSdk = 26
        // targetSdk 28 on purpose. From targetSdk 29 Android forbids an app from
        // executing files in its own data directory (SELinux untrusted_app), and
        // the player's whole userland lives there, under proot. This is the same
        // trade Termux makes. The APK is meant to be sideloaded, not published.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    // proot ships as lib*.so and must exist as a real file in nativeLibraryDir,
    // because that is the one place an app may execute from. Without this the
    // libraries stay compressed inside the APK and RB_PROOT would point at nothing.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload build: signed with the debug key so `assembleRelease` installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
