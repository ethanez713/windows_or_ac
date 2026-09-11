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
        // Google's read-only mirror of Maven Central, tried first: direct repo.maven.apache.org
        // pulls of the larger test artifacts (icu4j, the Robolectric android-all runtime) time
        // out on this connection. Same coordinates, faster host. mavenCentral() remains the
        // fallback for anything the mirror doesn't carry.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "AcWindows"
include(":app")
