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

rootProject.name = "Operit"
include(":app")
include(":dragonbones")
project(":dragonbones").projectDir = file("avator/dragonbones")
include(":terminal")
include(":mmd")
project(":mmd").projectDir = file("avator/mmd")
include(":fbx")
project(":fbx").projectDir = file("avator/fbx")
include(":quickjs")
