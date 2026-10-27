plugins { id("uit.jvm") }
dependencies {
    api(project(":core:network:common"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.mock)
}
