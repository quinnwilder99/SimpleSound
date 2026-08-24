import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// ktlint + detekt are wired here (rather than per-module) so every module gets the
// same style/static-analysis config for free — CI runs `ktlintCheck detekt` at the
// root, which fans out to every module below.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<KtlintExtension> {
        android.set(true)
    }

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        // Grandfathers every finding that already existed when detekt was adopted,
        // so CI only fails on issues introduced from here on. Regenerate with
        // `./gradlew detektBaseline` after a deliberate cleanup pass.
        baseline = file("$rootDir/config/detekt/baseline-${project.name}.xml")
    }
}
