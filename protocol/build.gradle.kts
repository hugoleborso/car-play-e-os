plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    api(libs.protobuf.java)
    implementation(libs.kotlin.stdlib)
}

protobuf {
    protoc {
        // Resolved from Maven Central, not from dl.google.com, so the build works
        // in restricted-egress environments.
        artifact = "${libs.protobuf.protoc.get().module}:${libs.versions.protobuf.get()}"
    }
}

sourceSets {
    main {
        java {
            srcDir(layout.buildDirectory.dir("generated/source/proto/main/java"))
        }
    }
}
