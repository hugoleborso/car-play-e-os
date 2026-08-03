dependencies {
    api(project(":core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
}
