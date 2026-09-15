buildscript {
    dependencies {
        // Hilt's Gradle plugin needs JavaPoet 1.13+ (older one can leak in via AGP).
        classpath("com.squareup:javapoet:1.13.0")
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
