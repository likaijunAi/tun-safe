
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}


rootProject.name = "tun-safe"
apply(from = "constants.gradle.kts")

include(":tun-safe-core")
include(":tun-safe-bootstrap")