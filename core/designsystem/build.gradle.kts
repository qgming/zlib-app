import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qgming.zlib.core.designsystem"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    api(libs.miuix.ui)
    api(libs.miuix.preference)
    api(libs.miuix.icons)
    api(libs.miuix.glass.icons)
    api(libs.miuix.material)
    api(libs.miuix.squircle)
    api(libs.miuix.blur)
    api(libs.miuix.glass)
    api(libs.miuix.nav)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}


