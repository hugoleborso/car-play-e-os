pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // google() is intentionally NOT listed here for the JVM modules: the
        // pure-JVM part of this project must build without any Android SDK.
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "openaap"

// ---------------------------------------------------------------------------
// Pure-JVM modules. These carry the entire protocol implementation and are
// buildable and testable on any machine with a JDK -- no Android SDK, no
// device, no emulator.
// ---------------------------------------------------------------------------
include(":protocol")
include(":crypto")
include(":transport")
include(":core")
include(":services")
include(":harness")

// ---------------------------------------------------------------------------
// Android modules. They are thin adapters over the JVM core (USB accessory
// transport, MediaCodec encoder, VirtualDisplay renderer, the app shell).
// They are only wired into the build when an Android SDK is actually present,
// so that `./gradlew build` keeps working in SDK-less environments such as CI
// sandboxes and the container this project was bootstrapped in.
// ---------------------------------------------------------------------------
val androidSdk: String? = System.getenv("ANDROID_SDK_ROOT")
    ?: System.getenv("ANDROID_HOME")
    ?: file("local.properties")
        .takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("=")

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":android:app")
    include(":android:transport-usb")
    include(":android:projection")
} else {
    logger.lifecycle(
        "openaap: no Android SDK found (ANDROID_SDK_ROOT/ANDROID_HOME/local.properties) -- " +
            "building JVM modules only. The Android modules under android/ are still in the " +
            "repository and compile once an SDK is available."
    )
}
