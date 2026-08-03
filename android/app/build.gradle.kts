plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.openaap.android.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openaap.projection"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            // BouncyCastle ships the same OSGi metadata in each of its jars, and
            // the merger will not pick one for us. It is only on the classpath
            // because certificate generation lives in the crypto module; if that
            // ever moves behind a platform-provider abstraction, this and about
            // five megabytes of APK go with it.
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":android:transport-usb"))
    implementation(project(":android:projection"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.androidx.core)
}
