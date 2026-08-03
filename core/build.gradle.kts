dependencies {
    api(project(":protocol"))
    api(project(":transport"))
    api(project(":crypto"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
}
