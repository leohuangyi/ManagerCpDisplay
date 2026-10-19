plugins {
    alias(libs.plugins.android.application)
}

// Minimal API 17 (Android 4.2.2) port of DiPlay: reuses the `shared` protocol/media core,
// drops the Compose `common` UI layer, and drives CarPlay through a native SurfaceView.
android {
    namespace = "com.shilapi.mgxcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shilapi.mgxcertplay"
        minSdk = 17
        targetSdk = 18
        versionCode = 1
        versionName = "0.1"

        multiDexEnabled = true

        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // This module intentionally targets Android 4.2.2 head units (minSdk 17).
        disable += "ExpiredTargetSdkVersion"
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.jmdns)
    coreLibraryDesugaring(libs.androidx.desugar.jdk.libs)
    implementation(libs.androidx.multidex)
}