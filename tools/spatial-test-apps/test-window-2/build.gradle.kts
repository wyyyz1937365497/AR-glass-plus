plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.arglass.spatialtest.window2"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.arglass.spatialtest.window2"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Shared calibration view compiled into every test APK.
    sourceSets["main"].kotlin.srcDir(rootProject.file("tools/spatial-test-apps/common"))
}
