pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "filasignal"
include("filascript-core", "filascript-cli", "filasignal-fabric")
