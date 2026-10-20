plugins { id("uit.jvm") }
dependencies {
    api(project(":core:model"))
    api(libs.coroutines)
    api(libs.datetime)
}
