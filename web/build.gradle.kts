@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "nsdl-web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            kotlin.srcDirs(
                "../src/main/kotlin/com/a2z/nsdl/sim",
                "../src/main/kotlin/com/a2z/nsdl/model",
                "../src/main/kotlin/com/a2z/nsdl/net",
                "../src/main/kotlin/com/a2z/nsdl/link",
                "../src/main/kotlin/com/a2z/nsdl/ip",
                "../src/main/kotlin/com/a2z/nsdl/dhcp",
                "../src/main/kotlin/com/a2z/nsdl/print",
                "../src/main/kotlin/com/a2z/nsdl/ssh",
                "../src/main/kotlin/com/a2z/nsdl/dns",
                "../src/main/kotlin/com/a2z/nsdl/http",
                "../src/main/kotlin/com/a2z/nsdl/device",
                "../src/main/kotlin/com/a2z/nsdl/app",
                "../src/main/kotlin/com/a2z/nsdl/events",
                "../src/main/kotlin/com/a2z/nsdl/ipc/json",
            )
        }
    }
}
