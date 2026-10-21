plugins { id("uit.jvm") }
dependencies {
    api(project(":core:common"))
    api(libs.ktor.core)
    implementation(libs.ktor.okhttp)
    api(libs.serialization)
    api(libs.ksoup)
    testImplementation(libs.ktor.mock)
    testImplementation(libs.coroutines.test)
}
