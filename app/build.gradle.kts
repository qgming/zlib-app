import java.util.Properties
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val appVersionName = "1.3.0"
val appVersionCode = 5

android {
    namespace = "com.qgming.zlib"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.qgming.zlib"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // ADR-071: one universal APK for releases and debug builds. Existing ABI-specific
    // installs can update to it through the updater's unique-universal fallback.
    splits {
        abi {
            isEnable = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.register<Sync>("prepareReleaseAssets") {
    group = "distribution"
    description = "Build one signed universal APK and SHA256SUMS.txt for a GitHub Release."
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("app-release.apk")
        rename { "zhishi-$appVersionName-universal.apk" }
    }
    into(layout.buildDirectory.dir("release"))
    doFirst {
        check(keystorePropertiesFile.exists()) { "A private key.properties signing configuration is required." }
    }
    doLast {
        val apks = destinationDir.listFiles().orEmpty().filter { it.extension == "apk" }.sortedBy { it.name }
        check(apks.map { it.name } == listOf("zhishi-$appVersionName-universal.apk")) {
            "Expected exactly one universal APK."
        }
        val checksums = apks.joinToString("\n", postfix = "\n") { apk ->
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            "${digest.digest().joinToString("") { "%02x".format(it) }}  ${apk.name}"
        }
        destinationDir.resolve("SHA256SUMS.txt").writeText(checksums, Charsets.UTF_8)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":feature:account"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:search"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:library"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.network.okhttp)

    // SharedViewDemo b449a426, SHA-256 and provenance in app/libs/README.md.
    implementation(files("libs/sharedview-kit-1.0.0.5.aar"))

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

