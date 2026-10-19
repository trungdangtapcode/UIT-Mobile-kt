plugins { base }
val ktlint by configurations.creating { attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) } }
val detekt by configurations.creating { attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) } }
dependencies {
    ktlint(libs.ktlint) {
        artifact { classifier = "all" }
        isTransitive = false
    }
    detekt(libs.detekt) {
        artifact { classifier = "all" }
        isTransitive = false
    }
}
val kotlinPatterns = listOf("**/src/**/*.kt", "*.gradle.kts", "build-logic/*.gradle.kts", "!**/build/**")
val formatKotlin by tasks.registering(JavaExec::class) {
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(listOf("--format") + kotlinPatterns)
}
val ktlintCheck by tasks.registering(JavaExec::class) {
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(kotlinPatterns)
}
val detektCheck by tasks.registering(JavaExec::class) {
    classpath = detekt
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args("--input", "core,feature,app", "--excludes", "**/build/**", "--config", "config/detekt.yml", "--report", "html:build/reports/detekt.html")
}
tasks.named("check") { dependsOn(ktlintCheck) }
