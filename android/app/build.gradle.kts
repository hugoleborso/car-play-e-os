plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
}

/**
 * The commit this APK was built from, so the phone can say which build it is
 * running.
 *
 * versionName is a hand-maintained string that nobody remembers to bump, which
 * makes it useless for answering "did I install the new one?" -- and getting
 * that wrong means driving to a car and testing the wrong build. The commit is
 * not a matter of anyone remembering.
 *
 * Falls back to "unknown" rather than failing: a shallow clone, an exported
 * tarball or a machine without git should still produce a working APK.
 */
val gitRevision: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }.orElse("unknown").get()
    .ifBlank { "unknown" }

android {
    namespace = "org.openaap.android.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openaap.projection"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        // Kept a plain literal: the release workflow greps this line, and the
        // revision travels in BuildConfig instead, where nothing has to parse it.
        versionName = "0.1.0"
        buildConfigField("String", "GIT_REVISION", "\"$gitRevision\"")
    }

    buildFeatures {
        buildConfig = true
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
