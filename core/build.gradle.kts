import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
    `maven-publish`
}

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/moemusicBuildInfo/main/kotlin")

val generateMoeMusicCoreBuildInfo = tasks.register("generateMoeMusicCoreBuildInfo") {
    val coreVersion = project.version.toString()
    val protocolVersion = libs.versions.moemusic.protocol.get()
    inputs.property("coreVersion", coreVersion)
    inputs.property("protocolVersion", protocolVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val coreInfoFile = generatedBuildInfoDir.get()
            .file("org/lolicode/moemusic/core/MoeMusicCoreBuildInfo.kt")
            .asFile
        coreInfoFile.parentFile.mkdirs()
        coreInfoFile.writeText(
            """
            package org.lolicode.moemusic.core

            object MoeMusicCoreBuildInfo {
                const val CORE_VERSION: String = "$coreVersion"
            }
            """.trimIndent() + "\n",
        )

        val protocolInfoFile = generatedBuildInfoDir.get()
            .file("org/lolicode/moemusic/core/protocol/MoeMusicProtocol.kt")
            .asFile
        protocolInfoFile.parentFile.mkdirs()
        protocolInfoFile.writeText(
            """
            package org.lolicode.moemusic.core.protocol

            object MoeMusicProtocol {
                const val VERSION: Int = $protocolVersion
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }

    sourceSets.named("main") {
        kotlin.srcDir(generatedBuildInfoDir)
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateMoeMusicCoreBuildInfo)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.named("sourcesJar") {
    dependsOn(generateMoeMusicCoreBuildInfo)
}

wire {
    kotlin {}
    sourcePath {
        srcDir("src/main/proto")
    }
}

dependencies {
    api(project(":api"))
    implementation(libs.lavaplayer)
    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)

    // Test
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
