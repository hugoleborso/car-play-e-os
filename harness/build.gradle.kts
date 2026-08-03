dependencies {
    implementation(project(":services"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.coroutines.test)
}
