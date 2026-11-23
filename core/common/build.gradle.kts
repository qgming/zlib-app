import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The non-Android JVM tests use the same source XML as Android Resources.
val generateDefaultAppTexts by tasks.registering {
    val strings = file("src/main/res/values/ui_strings.xml")
    val output = layout.buildDirectory.file("generated/appTexts/com/qgming/zlib/core/common/DefaultAppTexts.kt")
    inputs.file(strings)
    outputs.file(output)
    doLast {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings)
        val nodes = document.getElementsByTagName("string")
        fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r") + "\""
        val rows = (0 until nodes.length).map { index ->
            val element = nodes.item(index) as org.w3c.dom.Element
            val value = element.textContent.removeSurrounding("\"").replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
            "        R.string.${element.getAttribute("name")} to ${quoted(value)},"
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""
                package com.qgming.zlib.core.common

                internal object DefaultAppTexts {
                    fun get(id: Int, arguments: Array<out Any?>): String =
                        String.format(java.util.Locale.SIMPLIFIED_CHINESE, values.getValue(id), *arguments)
                    private val values = mapOf(
                ${rows.joinToString("\n")}
                    )
                }
            """.trimIndent())
        }
    }
}

android {
    namespace = "com.qgming.zlib.core.common"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/appTexts").get().asFile)
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach { dependsOn(generateDefaultAppTexts) }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}


