// Root build script: plugin versions come from gradle/libs.versions.toml (KT-SEC-03).
// AGP 9 built-in Kotlin: `org.jetbrains.kotlin.android` is gone; AGP's built-in KGP is
// 2.2.10, so raise the Kotlin Gradle plugin on the build classpath to our pin (per
// developer.android.com/build/migrate-to-built-in-kotlin).
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.protobuf) apply false
    alias(libs.plugins.ksp) apply false // verified on the forced-KGP-2.4.20 combo
    alias(libs.plugins.androidx.room3) apply false
}
