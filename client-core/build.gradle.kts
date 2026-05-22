import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/moemusicBuildInfo/main/kotlin")

val generateMoeMusicClientCoreBuildInfo by tasks.registering {
    val clientCoreVersion = project.version.toString()
    inputs.property("clientCoreVersion", clientCoreVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val outputFile = generatedBuildInfoDir.get()
            .file("org/lolicode/moemusic/clientcore/MoeMusicClientCoreBuildInfo.kt")
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package org.lolicode.moemusic.clientcore

            object MoeMusicClientCoreBuildInfo {
                const val CLIENT_CORE_VERSION: String = "$clientCoreVersion"
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
    dependsOn(generateMoeMusicClientCoreBuildInfo)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.named("sourcesJar") {
    dependsOn(generateMoeMusicClientCoreBuildInfo)
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.lavaplayer)
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
