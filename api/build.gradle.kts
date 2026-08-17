import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/moemusicBuildInfo/main/kotlin")

val generateMoeMusicApiBuildInfo = tasks.register("generateMoeMusicApiBuildInfo") {
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
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
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
    // Guaranteed baseline runtime libraries exported to all consumers (core, platforms, plugins)
    api(libs.kotlin.stdlib)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.core)
    api(libs.kotlinx.serialization.json)
    api(libs.slf4j.api)
}
