plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.0"
}

group = "com.crudtool"
version = "0.23"

repositories {
    mavenCentral()
    intellijPlatform {
        // 使用本地 IDE 时，需要显式注册本地平台制品仓库，否则 bundledPlugin 无法解析其平台路径
        localPlatformArtifacts()
    }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.4")
    // MyBatis XML 中 SQL 格式化（vertical-blank/sql-formatter，JS sql-formatter 的 Java 移植）
    implementation("com.github.vertical-blank:sql-formatter:2.0.4")

    intellijPlatform {
        // 使用本地 IDE 作为 SDK，避免从 JetBrains 仓库下载（本机网络无法访问其 CDN）
        local("F:/IntelliJ IDEA 2026.2.0.1")
    }

    // bundledPlugin("com.intellij.java") 在 local() 场景下存在 provider 空值缺陷，
    // 这里直接以 compileOnly 方式引入本地 Java 插件 lib 下的 jar，提供 Java PSI 类，
    // 且不会被打进最终的插件包（运行时由 IDE 提供）。
    compileOnly(fileTree("F:/IntelliJ IDEA 2026.2.0.1/plugins/java/lib") {
        include("**/*.jar")
    })

    // Database 插件（com.intellij.database）的模型 API，仅编译期使用，运行时由 IDE 提供
    compileOnly(fileTree("F:/IntelliJ IDEA 2026.2.0.1/plugins/DatabaseTools/lib") {
        include("modules/intellij.database.jar", "modules/intellij.database.util.jar")
    })
}

java {
    toolchain {
        // IDEA 2026.2.0.1 的 jar 为 class file version 69，需 JDK 25 编译
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// 本插件无需 IntelliJ 字节码插桩，且 local() 环境下 JavaCompiler 依赖无法解析，直接禁用
tasks.named("instrumentCode") {
    enabled = false
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    patchPluginXml {
        sinceBuild.set("252")
    }

    buildPlugin

    runIde {
        jvmArgs(
            "-Xmx4096m",
            "-XX:ReservedCodeCacheSize=1024m"
        )
    }
}