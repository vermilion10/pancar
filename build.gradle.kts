// AGP 9.x has built-in Kotlin support, so the org.jetbrains.kotlin.android plugin is not applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
