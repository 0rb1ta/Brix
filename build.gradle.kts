plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
}

// The root-only `ktlint` plugin previously only linted this file's own
// build.gradle.kts (no src/ at the root) — every module's real Kotlin source
// was never actually checked despite D5/CI claiming ktlint coverage.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
