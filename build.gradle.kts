import org.gradle.api.plugins.BasePlugin
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.wire) apply false
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(alias: String): String =
    libsCatalog.findVersion(alias).orElseThrow {
        GradleException("Missing version catalog entry '$alias'.")
    }.requiredVersion

val versionSuffix = providers.gradleProperty("moemusic.versionSuffix")
    .orElse(
        providers.gradleProperty("moemusic.snapshot").map { snapshot ->
            when (snapshot.lowercase()) {
                "true" -> "-SNAPSHOT"
                "false" -> ""
                else -> throw GradleException("moemusic.snapshot must be 'true' or 'false', got '$snapshot'.")
            }
        },
    )
    .orElse("")

fun moduleVersion(alias: String): String = catalogVersion(alias) + versionSuffix.get()

fun nonBlankEnvironmentVariable(name: String) =
    providers.provider {
        System.getenv(name)?.takeIf { it.isNotBlank() }
    }

val moduleVersions = mapOf(
    "api" to moduleVersion("moemusic-api"),
    "core" to moduleVersion("moemusic-core"),
    "client-core" to moduleVersion("moemusic-client-core"),
)

tasks.register("checkPublicApi") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the committed ABI dump for MoeMusic's supported public API."
    dependsOn(":api:checkKotlinAbi")
}

tasks.register("updatePublicApi") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Updates the committed ABI dump for MoeMusic's supported public API."
    dependsOn(":api:updateKotlinAbi")
}

tasks.register("publishSharedToMavenLocal") {
    group = BasePlugin.BUILD_GROUP
    description = "Publishes api/core/client-core to Maven Local."
    dependsOn(
        ":api:publishToMavenLocal",
        ":core:publishToMavenLocal",
        ":client-core:publishToMavenLocal",
    )
}

tasks.register("publishSharedToStaging") {
    group = BasePlugin.BUILD_GROUP
    description = "Publishes api/core/client-core to local R2 staging directory."
    dependsOn(
        ":api:publishMavenJavaPublicationToLocalR2StagingRepository",
        ":core:publishMavenJavaPublicationToLocalR2StagingRepository",
        ":client-core:publishMavenJavaPublicationToLocalR2StagingRepository",
    )
}

fun sharedModuleTaskNamePart(moduleName: String): String =
    moduleName.split("-").joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }

val publishableSharedModules = listOf("api", "core", "client-core")

publishableSharedModules.forEach { moduleName ->
    tasks.register("publish${sharedModuleTaskNamePart(moduleName)}ToPackageRegistries") {
        group = "publishing"
        description = "Publishes :$moduleName to local R2 staging and GitHub Packages."
        dependsOn(
            ":$moduleName:publishMavenJavaPublicationToLocalR2StagingRepository",
            ":$moduleName:publishMavenJavaPublicationToGitHubPackagesRepository",
        )
    }
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.walkyst\\..*") }
        }
        maven {
            name = "Lolicode Releases"
            url = uri("https://maven.lolicode.org/releases")
            content { includeGroupByRegex("org\\.lolicode.*") }
        }
        maven {
            name = "Lolicode Snapshots"
            url = uri("https://maven.lolicode.org/snapshots")
            content { includeGroupByRegex("org\\.lolicode.*") }
        }
    }
}

subprojects {
    group = "org.lolicode.moemusic"
    version = moduleVersions[name]
        ?: throw GradleException("No MoeMusic version configured for shared module '$name'.")

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            publications {
                if (findByName("mavenJava") == null) {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])
                    }
                }
            }

            repositories {
                maven {
                    name = "LocalR2Staging"
                    val stagingDir = providers.gradleProperty("moemusic.publish.stagingDir")
                        .orElse(nonBlankEnvironmentVariable("STAGING_DIR"))
                        .orElse(rootProject.layout.buildDirectory.dir("r2-staging").map { it.asFile.absolutePath })
                    url = uri(rootProject.file(stagingDir.get()))
                }
                maven {
                    name = "GitHubPackages"
                    val repository = providers.gradleProperty("moemusic.githubPackagesRepository")
                        .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
                        .orElse("lolicode-org/MoeMusic")
                        .get()
                    url = uri("https://maven.pkg.github.com/$repository")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(nonBlankEnvironmentVariable("GITHUB_ACTOR"))
                            .orElse("")
                            .get()
                        password = providers.gradleProperty("gpr.key")
                            .orElse(nonBlankEnvironmentVariable("GITHUB_PACKAGES_TOKEN"))
                            .orElse(nonBlankEnvironmentVariable("GITHUB_TOKEN"))
                            .orElse("")
                            .get()
                    }
                }
            }
        }
    }
}
