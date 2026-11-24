plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9.x has built-in Kotlin support; org.jetbrains.kotlin.android is not compatible.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
