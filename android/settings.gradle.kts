rootProject.name = "LxBox"

// The aliyun mirrors occasionally answer 502, which Gradle treats as a
// repository failure rather than a clean 404 and disables the whole source
// for the run. They exist for local dev behind the GFW; CI (GitHub runners)
// reaches google()/mavenCentral() directly, so they are only wired in when
// the ABILOX_LOCAL_ALIYUN env var (or -PaliyunMirror) is set.

fun useAliyunMirror(): Boolean =
    System.getenv("ABILOX_LOCAL_ALIYUN") == "1" ||
        gradle.startParameter.projectProperties.containsKey("aliyunMirror")

pluginManagement {
    val useMirror = System.getenv("ABILOX_LOCAL_ALIYUN") == "1" ||
        gradle.startParameter.projectProperties.containsKey("aliyunMirror")
    repositories {
        if (useMirror) {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val useMirror = System.getenv("ABILOX_LOCAL_ALIYUN") == "1" ||
        gradle.startParameter.projectProperties.containsKey("aliyunMirror")
    repositories {
        if (useMirror) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
    }
}

include(":app")
