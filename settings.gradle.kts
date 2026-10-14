pluginManagement {
    repositories {
        // 本机实测：repo1.maven.org DNS 超时，阿里云镜像优先（见 docs/environment.md）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.maven.apache.org/maven2")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.maven.apache.org/maven2")
        google()
        mavenCentral()
    }
}

rootProject.name = "EchoMind"
include(":app")
