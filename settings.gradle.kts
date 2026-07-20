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
        mavenCentral() // Wiliot SDK is published here (com.wiliot:*)
        maven { url = uri("https://jitpack.io") } // transitive dep: com.github.hannesa2:paho.mqtt.android
    }
}

rootProject.name = "WiliotPixelProximity"
include(":app")
