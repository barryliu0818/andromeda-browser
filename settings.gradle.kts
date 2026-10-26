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
        // Haze 2.1 快照（只給 Haze 用）
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            content { includeGroup("dev.chrisbanes.haze") }
            mavenContent { snapshotsOnly() }
        }
    }
}

rootProject.name = "Andromeda"
include(":app", ":baselineprofile")
