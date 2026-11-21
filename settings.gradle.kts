pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://repo.extendedclip.com/releases/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "stonyperms-bridge"

include("core", "paper", "fabric")
