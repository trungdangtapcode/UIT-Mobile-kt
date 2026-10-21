plugins { id("uit.jvm") }
dependencies {
    api(project(":core:network:common"))
    testImplementation(libs.ktor.mock)
    testImplementation(libs.coroutines.test)
}
