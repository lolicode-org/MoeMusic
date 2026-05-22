pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "moemusic-shared"

include(":api")
include(":core")
include(":client-core")
