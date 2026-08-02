plugins {
    alias(libs.plugins.android.library)
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.openaap.android.usb"
    compileSdk = 35
    defaultConfig {
        // Android 10. Below that the accessory-mode plumbing differs enough to
        // need its own testing, and /e/OS targets are all well above it.
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":transport"))
    implementation(libs.kotlin.stdlib)
}
