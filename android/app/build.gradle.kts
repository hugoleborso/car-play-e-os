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

/**
 * versionCode, derived from the number of commits rather than maintained.
 *
 * It sat at 1 through every release, which is not cosmetic: Android compares
 * versionCode to decide whether an APK is an update, so a phone can decline to
 * install a newer build over an older one carrying the same number. Counting
 * commits increases monotonically on any branch that only moves forward, and
 * nobody has to remember it.
 */
val buildNumber: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim() }.orElse("").get()
    .toIntOrNull() ?: 1

android {
    namespace = "org.openaap.android.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openaap.projection"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        // 0.4.0: the projection failure became an experiment. Eight variants of
        // what the phone does after the certificate is accepted, one per
        // connection, the way the credential matrix works.
        //
        // 0.3.0 was the projection report; 0.2.0 the projection path itself;
        // 0.1.0 the probe.
        //
        // Kept a plain literal: the release workflow greps this line, and the
        // revision travels in BuildConfig instead, where nothing has to parse it.
        versionName = "0.4.0"
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
