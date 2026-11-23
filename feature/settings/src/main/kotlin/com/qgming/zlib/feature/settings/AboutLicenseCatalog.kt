package com.qgming.zlib.feature.settings

/** Offline attribution entries. Families share license text, never another project's copyright. */
data class AboutLicenseEntry(val id: String, val title: String, val license: String, val body: String)

internal fun AboutLicenses.entries(): List<AboutLicenseEntry> {
    fun entry(id: String, title: String, license: String, source: String, attribution: String, vararg texts: String) =
        AboutLicenseEntry(id, title, license, (listOf(source, attribution) + texts).filter(String::isNotBlank).joinToString("\n\n"))

    fun apache(id: String, title: String, source: String, attribution: String) =
        entry(id, title, "Apache-2.0", source, attribution, apache)

    return listOf(
        entry("project", "Zlib", "MIT", "https://github.com/qgming/zlib-app", "", project),
        entry("heartleo", "heartleo/zlib", "MIT", "https://github.com/heartleo/zlib", "EAPI behavior reference.", heartleo),
        apache("miuix", "Miuix", "https://github.com/compose-miuix-ui/miuix", "Miuix contributors."),
        apache("compose", "AndroidX Compose", "https://android.googlesource.com/platform/frameworks/support/", "The Android Open Source Project. Compose UI, runtime, animation, foundation and window size classes."),
        apache("activity", "AndroidX Activity", "https://android.googlesource.com/platform/frameworks/support/", "The Android Open Source Project. Activity and Activity Compose."),
        apache("lifecycle", "AndroidX Lifecycle", "https://android.googlesource.com/platform/frameworks/support/", "The Android Open Source Project. Lifecycle, ViewModel and LiveData."),
        apache("navigation", "AndroidX Navigation", "https://android.googlesource.com/platform/frameworks/support/", "The Android Open Source Project. Navigation and NavigationEvent."),
        apache("room", "AndroidX Room / SQLite", "https://android.googlesource.com/platform/frameworks/support/", "The Android Open Source Project."),
        apache("datastore", "AndroidX DataStore", "https://android.googlesource.com/platform/frameworks/support/", "The Android Open Source Project. Preferences and bundled protobuf runtime."),
        apache("documentfile", "AndroidX DocumentFile", "https://android.googlesource.com/platform/frameworks/support/", "The Android Open Source Project."),
        apache("androidx", "AndroidX Core / Support", "https://android.googlesource.com/platform/frameworks/support/", "The Android Open Source Project. Core, annotations, appcompat resources, arch core, autofill, collection, concurrent futures, customview, emoji2, exifinterface, fragment, graphics, Hilt integration, interpolator, loader, profileinstaller, savedstate, startup, tracing, vectordrawable, versionedparcelable, viewpager and window."),
        apache("compose-multiplatform", "Compose Multiplatform", "https://github.com/JetBrains/compose-multiplatform", "JetBrains and the Android Open Source Project. Compose and AndroidX multiplatform adaptations."),
        apache("kotlin", "Kotlin", "https://github.com/JetBrains/kotlin", "JetBrains and Kotlin Programming Language contributors."),
        apache("coroutines", "kotlinx.coroutines", "https://github.com/Kotlin/kotlinx.coroutines", "JetBrains and contributors."),
        apache("serialization", "kotlinx.serialization", "https://github.com/Kotlin/kotlinx.serialization", "JetBrains and contributors."),
        apache("atomicfu", "kotlinx.atomicfu", "https://github.com/Kotlin/kotlinx-atomicfu", "JetBrains and contributors."),
        apache("immutable-collections", "kotlinx.collections.immutable", "https://github.com/Kotlin/kotlinx.collections.immutable", "JetBrains and contributors."),
        apache("okhttp", "OkHttp", "https://github.com/square/okhttp", "Square, Inc. and contributors."),
        apache("okio", "Okio", "https://github.com/square/okio", "Square, Inc. and contributors."),
        apache("coil", "Coil", "https://github.com/coil-kt/coil", "Coil contributors. Core, Compose and OkHttp network integration."),
        apache("dagger", "Dagger / Hilt", "https://github.com/google/dagger", "The Dagger authors."),
        apache("javax-inject", "javax.inject", "https://github.com/javax-inject/javax-inject", "The JSR-330 specification authors."),
        entry("jakarta-inject", "Jakarta Dependency Injection", "Apache-2.0", "https://github.com/jakartaee/inject", "", jakartaNotice, apache),
        apache("accompanist", "Accompanist Drawable Painter", "https://github.com/google/accompanist", "The Android Open Source Project and Accompanist contributors."),
        entry("materialkolor", "MaterialKolor Color Utilities", "MIT / Apache-2.0", "https://github.com/jordond/materialkolor", "MaterialKolor contributors. Includes upstream Google Material Color Utilities.", materialkolor, apache),
        apache("poko", "Poko Annotations", "https://github.com/drewhamilton/Poko", "Drew Hamilton and contributors."),
        apache("jetbrains-annotations", "JetBrains Java Annotations", "https://github.com/JetBrains/java-annotations", "JetBrains and contributors."),
        apache("jspecify", "JSpecify", "https://github.com/jspecify/jspecify", "The JSpecify Authors."),
        apache("jsr305", "FindBugs JSR-305 Annotations", "https://central.sonatype.com/artifact/com.google.code.findbugs/jsr305/3.0.2", "License declared by the published com.google.code.findbugs:jsr305:3.0.2 artifact."),
        apache("listenablefuture", "Guava ListenableFuture", "https://github.com/google/guava", "The Guava Authors."),
        entry("sharedview", "SharedView Kit 1.0.0.5", "", "https://gitee.com/itgsa_sharedviewkit/SharedViewDemo", "", sharedView),
    )
}
