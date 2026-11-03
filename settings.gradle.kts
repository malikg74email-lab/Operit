pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://dl.bintray.com/rikkaw/Shizuku") }
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    }
}

rootProject.name = "Shaheen"
include(":app")
include(":terminal")
// Wave 5 step 5c: :dragonbones/:mmd/:fbx (avator/) are no longer included — the 3D
// avatar stack is removed. The avator/ directories stay in the repo as orphans and
// contribute nothing to the build or the APK.
include(":quickjs")
