plugins {
    alias(libs.plugins.android.application)
}

// Minimal API 17 (Android 4.2.2) port of DiPlay: reuses the `shared` protocol/media core,
// drops the Compose `common` UI layer, and drives CarPlay through a native SurfaceView.
android {
    namespace = "local.airuize.diplaylegacy"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "local.airuize.diplaylegacy"
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
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.jmdns)
    coreLibraryDesugaring(libs.androidx.desugar.jdk.libs)
    implementation(libs.androidx.multidex)
}