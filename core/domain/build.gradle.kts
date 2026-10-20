plugins { id("uit.jvm"); `java-test-fixtures` }
dependencies {
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    api(project(":core:common"))
}
