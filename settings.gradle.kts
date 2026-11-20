import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
        mavenCentral()
    }
}

rootProject.name = "zhxs"

// lib
include(":lib-base")
include(":lib-compose")
include(":lib-common:comm-archive")
include(":lib-dm:dm-view")
include(":lib-player:dkplayer-java")
include(":lib-player:player-exo")
include(":lib-player:player-mpv")
include(":lib-player:player-ui")

// app
include(":dy-player")

val localSettings = file("settings.local.gradle.kts")
if (localSettings.exists()) {
    apply(from = localSettings)
}
