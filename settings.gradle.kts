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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Solo sherpa-onnx (JitPack reempaqueta el AAR de la release de GitHub).
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.k2-fsa.*") }
        }
    }
}
rootProject.name = "mysone"
include(":app")
