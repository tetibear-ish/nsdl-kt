plugins {
    kotlin("jvm") version "2.4.20"
    application
    id("org.beryx.runtime") version "1.13.1"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

sourceSets.main {
    resources.srcDir("web/src/wasmJsMain/resources")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("test.runtime.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

application {
    mainClass = "com.a2z.nsdl.MainKt"
}

runtime {
    // java.net.http and jdk.httpserver back the `web` subcommand's HTTP/SSE transport;
    // everything else the app touches lives in java.base.
    modules.addAll("java.base", "java.net.http", "jdk.httpserver")
    options.addAll("--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2")
}
