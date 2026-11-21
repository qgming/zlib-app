pluginManagement {
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "org.jetbrains.kotlin.android" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")

                "org.jetbrains.kotlin.plugin.compose" ->
                    useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        maven("https://maven.google.com")
        maven("https://repo.maven.apache.org/maven2")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        // Explicit fallbacks: the machine-wide init script points Maven Central and
        // dl.google.com at Aliyun, which lags newly published artifacts (Miuix beta).
        maven("https://maven.google.com")
        maven("https://repo.maven.apache.org/maven2")
    }
}

rootProject.name = "zlib-app"

include(
    ":app",
    ":core:common",
    ":core:model",
    ":core:designsystem",
    ":core:network",
    ":core:database",
    ":core:data",
    ":feature:search",
    ":feature:detail",
    ":feature:downloads",
    ":feature:library",
    ":feature:account",
    ":feature:settings",
    ":feature:metadata",
    ":feature:booklists",
)
