plugins { id("uit.android.library"); alias(libs.plugins.ksp) }
dependencies {
    api(project(":core:domain"))
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.gson)
    ksp(libs.room.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
