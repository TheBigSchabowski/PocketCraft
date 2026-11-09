pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketCraft"
// Desktop-App (macOS / Linux)
include(":desktop")

// Android-App nur, wenn ein Android SDK vorhanden ist – so baut ein reiner Linux-Server die Desktop-Pakete.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().lineSequence().any { l -> l.startsWith("sdk.dir=") } }
if (hasAndroidSdk) {
    include(":app")
} else {
    println("Kein Android SDK gefunden – :app wird übersprungen, nur :desktop wird gebaut.")
}
