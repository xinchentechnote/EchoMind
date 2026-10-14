// 仓库顺序按环境切换，两边都实测过（数值与结论见 docs/environment.md §3、§8）：
// - 本机：repo1.maven.org 与 search.maven.org 的 DNS 直接超时，必须阿里云镜像优先。
// - GitHub Actions：runner 打 maven.aliyun.com 的 gradle-plugin / public 两个仓库会返回 **HTTP 502**
//   （实测 run 38631763968 的探测矩阵：KSP marker 在本机 4 个源全是 200，在 runner 上 502），
//   而 Gradle 把 5xx 当解析错误、不再往后找下一个仓库，于是报「plugin not found」。
//   所以 CI 上（GITHUB_ACTIONS=true）只用官方仓库。
// 想在本地复现 CI 的解析路径：GITHUB_ACTIONS=true ./gradlew --refresh-dependencies help
// 注意：pluginManagement / dependencyResolutionManagement 块里引用不到脚本顶层的变量，
// 所以判断只能各写一遍（实测把 `val preferAliyun` 提到顶层会直接脚本编译失败）。
pluginManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        maven("https://repo.maven.apache.org/maven2")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        maven("https://repo.maven.apache.org/maven2")
        google()
        mavenCentral()
    }
}

rootProject.name = "EchoMind"
include(":app")
