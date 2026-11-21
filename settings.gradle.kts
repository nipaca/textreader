pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only used at build time for the offline OCR library (Tesseract4Android).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TextReader"
include(":app")
