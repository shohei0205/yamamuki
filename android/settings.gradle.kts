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
    }
}

rootProject.name = "yamamuki"

// Android に依存しないデータ取得ロジック。単体でもビルド・テストできるよう独立ビルドにしている。
includeBuild("core")
include(":app")
