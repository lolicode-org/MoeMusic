import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/moemusicBuildInfo/main/kotlin")

val generateMoeMusicApiBuildInfo by tasks.registering {
    val apiVersion = libs.versions.moemusic.api.compat.get()
    inputs.property("apiVersion", apiVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val outputFile = generatedBuildInfoDir.get()
            .file("org/lolicode/moemusic/api/MoeMusicApiBuildInfo.kt")
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package org.lolicode.moemusic.api

            internal object MoeMusicApiBuildInfo {
                internal const val API_VERSION: String = "$apiVersion"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        jvmDefault = JvmDefaultMode.ENABLE
    }

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        filters {
            // Freeze only the supported plugin API surface.
            include {
                byNames.add("org.lolicode.moemusic.api.**")
            }
        }
    }

    sourceSets.named("main") {
        kotlin.srcDir(generatedBuildInfoDir)
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateMoeMusicApiBuildInfo)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.named("sourcesJar") {
    dependsOn(generateMoeMusicApiBuildInfo)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.core)
    // SLF4J facade — used for Logger type in PluginContext; runtime provided by Minecraft/the loader
    compileOnly(libs.slf4j.api)
}
