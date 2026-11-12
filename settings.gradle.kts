pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // Optional extra repository (e.g. an offline mirror of Google Maven artifacts): -PlocalAndroidxRepo=/path
        providers.gradleProperty("localAndroidxRepo").orNull?.let { maven(it) }
    }
}
rootProject.name = "inkoma"
include(":source-api", ":app", ":test-extension")
