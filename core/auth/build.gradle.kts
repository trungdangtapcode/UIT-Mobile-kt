plugins { id("uit.android.library") }
dependencies {
    api(project(":core:common"))
    implementation(libs.datastore)
    implementation(libs.serialization)
}
