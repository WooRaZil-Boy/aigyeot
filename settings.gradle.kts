pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://nexus-playground.pleos.ai/repository/maven-releases/") }
}
rootProject.name = "aigyeot"
include(":app", ":engine", ":shared", ":phone")
project(":phone").projectDir = file("phone/android")
