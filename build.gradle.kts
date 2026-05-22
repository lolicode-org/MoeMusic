import org.gradle.api.plugins.BasePlugin
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.authentication.http.HttpHeaderAuthentication
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
            if (snapshot.toBooleanStrictOrNull() == true) "-SNAPSHOT" else ""
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

fun sharedModuleTaskNamePart(moduleName: String): String =
    moduleName.split("-").joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }

val publishableSharedModules = listOf("api", "core", "client-core")

publishableSharedModules.forEach { moduleName ->
    tasks.register("publish${sharedModuleTaskNamePart(moduleName)}ToPackageRegistries") {
        group = "publishing"
        description = "Publishes :$moduleName to GitHub Packages and Codeberg Packages."
        dependsOn(
            ":$moduleName:publishMavenJavaPublicationToGitHubPackagesRepository",
            ":$moduleName:publishMavenJavaPublicationToCodebergPackagesRepository",
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
            name = "Lolicode on Codeberg"
            url = uri("https://codeberg.org/api/packages/lolicode/maven")
            content { includeGroupByRegex("org\\.lolicode.*")}
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
                maven {
                    name = "CodebergPackages"
                    val owner = providers.gradleProperty("moemusic.codebergPackagesOwner")
                        .orElse(nonBlankEnvironmentVariable("CODEBERG_PACKAGES_OWNER"))
                        .orElse("lolicode")
                        .get()
                    url = uri("https://codeberg.org/api/packages/$owner/maven")
                    credentials(HttpHeaderCredentials::class) {
                        name = "Authorization"
                        value = providers.gradleProperty("codeberg.token")
                            .orElse(nonBlankEnvironmentVariable("CODEBERG_TOKEN"))
                            .map { "token $it" }
                            .orElse("")
                            .get()
                    }
                    authentication {
                        create<HttpHeaderAuthentication>("header")
                    }
                }
            }
        }
    }
}
