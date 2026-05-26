package org.lolicode.moemusic.core.audio

import org.lolicode.lavaplayer.common.natives.NativeLibraryLoader
import org.lolicode.moemusic.core.platform.PlatformDirectories
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions

/**
 * Shared LavaPlayer native extraction setup.
 *
 * Core code intentionally does not discover Minecraft game paths on its own. Loader modules pass
 * those paths here before any LavaPlayer manager can trigger native loading.
 */
object LavaPlayerNativeBootstrap {

    private val logger = LoggerFactory.getLogger(LavaPlayerNativeBootstrap::class.java)
    private val lock = Any()

    private const val EXTRACT_PATH_PROPERTY = "lava.native.extractPath"
    private const val EXTRACT_MODE_PROPERTY = "lava.native.extractMode"
    private const val MOD_ID = "moemusic"
    private const val NATIVES_DIR = "lavaplayer-natives"

    @Volatile
    private var configured = false

    fun configure(configDir: Path, gameDir: Path? = null) {
        synchronized(lock) {
            if (configured) return

            val existingExtractionPath = System.getProperty(EXTRACT_PATH_PROPERTY).orEmpty().trim()
            if (existingExtractionPath.isNotEmpty()) {
                configured = true
                logger.info("LavaPlayer native extraction path already configured: {}", existingExtractionPath)
                return
            }

            val extractionPath = selectUsableExtractionPath(
                gameDir = gameDir,
                configDir = configDir,
                osName = System.getProperty("os.name").orEmpty(),
                env = System.getenv(),
                userHome = System.getProperty("user.home").orEmpty(),
                tempDir = System.getProperty("java.io.tmpdir").orEmpty(),
            )

            if (extractionPath == null) {
                logger.warn("No usable LavaPlayer native extraction directory found; LavaPlayer will use its default.")
                return
            }

            runCatching {
                setNativeExtractionPath(extractionPath)
            }.onSuccess {
                configured = true
                logger.info("Configured LavaPlayer native extraction directory: {}", extractionPath)
            }.onFailure { error ->
                logger.warn(
                    "Failed to configure LavaPlayer native extraction directory {}; LavaPlayer will use its default.",
                    extractionPath,
                    error,
                )
            }
        }
    }

    internal fun selectUsableExtractionPath(
        gameDir: Path?,
        configDir: Path,
        osName: String,
        env: Map<String, String>,
        userHome: String,
        tempDir: String,
    ): Path? {
        val failures = mutableListOf<String>()

        for (candidate in candidateExtractionPaths(gameDir, configDir, osName, env, userHome, tempDir)) {
            val normalized = candidate.toAbsolutePath().normalize()
            val result = runCatching {
                ensureUsableDirectory(normalized)
                normalized
            }
            if (result.isSuccess) {
                return normalized
            }

            failures += "$normalized (${result.exceptionOrNull()?.message ?: "unknown error"})"
        }

        if (failures.isNotEmpty()) {
            logger.warn("Rejected LavaPlayer native extraction candidates: {}", failures.joinToString("; "))
        }
        return null
    }

    internal fun candidateExtractionPaths(
        gameDir: Path?,
        configDir: Path,
        osName: String,
        env: Map<String, String>,
        userHome: String,
        tempDir: String,
    ): List<Path> {
        val candidates = mutableListOf<Path>()

        gameDir?.let {
            candidates.add(it.resolve("cache").resolve(MOD_ID).resolve(NATIVES_DIR))
        }

        osCacheDirectory(osName, env, userHome)?.let {
            candidates.add(it.resolve(MOD_ID).resolve(NATIVES_DIR))
        }

        candidates.add(configDir.resolve("cache").resolve(NATIVES_DIR))

        if (tempDir.isNotBlank()) {
            candidates.add(Paths.get(tempDir).resolve(MOD_ID).resolve(NATIVES_DIR))
        }

        return candidates.distinctBy { it.toAbsolutePath().normalize() }
    }

    private fun osCacheDirectory(osName: String, env: Map<String, String>, userHome: String): Path? {
        val normalizedOs = osName.lowercase()
        return when {
            "win" in normalizedOs -> {
                val base = env["LOCALAPPDATA"].orEmpty().ifBlank { env["APPDATA"].orEmpty() }
                when {
                    base.isNotBlank() -> Paths.get(base)
                    else -> PlatformDirectories.homeDirectory(env, userHome, preferWindowsHome = true)
                        ?.resolve("AppData")
                        ?.resolve("Local")
                }
            }

            "mac" in normalizedOs || "darwin" in normalizedOs ->
                PlatformDirectories.homeDirectory(env, userHome)
                    ?.resolve("Library")
                    ?.resolve("Caches")

            else -> {
                val xdgCacheHome = env["XDG_CACHE_HOME"].orEmpty()
                when {
                    xdgCacheHome.isNotBlank() -> Paths.get(xdgCacheHome)
                    else -> PlatformDirectories.homeDirectory(env, userHome)?.resolve(".cache")
                }
            }
        }
    }

    private fun setNativeExtractionPath(path: Path) {
        if (System.getProperty(EXTRACT_MODE_PROPERTY).isNullOrBlank()) {
            try {
                NativeLibraryLoader.setDefaultExtractionPath(
                    path,
                    NativeLibraryLoader.ExtractionMode.CONTENT_ADDRESSED_CACHE,
                )
                return
            } catch (error: LinkageError) {
                logger.warn(
                    "LavaPlayer extraction-mode API unavailable; falling back to path-only configuration.",
                    error,
                )
            }
        }

        NativeLibraryLoader.setDefaultExtractionPath(path)
    }

    private fun ensureUsableDirectory(path: Path) {
        createDirectoriesSecurely(path)

        val probe = Files.createTempFile(path, ".moemusic-lavaplayer-", ".probe")
        try {
            Files.newOutputStream(probe, StandardOpenOption.WRITE).use { it.write(0) }
        } finally {
            runCatching { Files.deleteIfExists(probe) }
                .onFailure { logger.debug("Failed to remove LavaPlayer native extraction probe {}.", probe, it) }
        }
    }

    private fun createDirectoriesSecurely(path: Path) {
        if (Files.isDirectory(path)) return
        if (Files.exists(path)) {
            throw IOException("Path exists but is not a directory")
        }

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        } else {
            Files.createDirectories(path)
        }
    }
}
