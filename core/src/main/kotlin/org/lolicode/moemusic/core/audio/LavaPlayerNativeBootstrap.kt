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
        userName: String = System.getProperty("user.name").orEmpty(),
    ): Path? {
        val failures = mutableListOf<String>()

        for (candidate in candidateExtractionPaths(gameDir, configDir, osName, env, userHome, tempDir, userName)) {
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
        userName: String = System.getProperty("user.name").orEmpty(),
        isAndroidOverride: Boolean? = null,
    ): List<Path> {
        val candidates = mutableListOf<Path>()
        val onAndroid = isAndroidOverride ?: isAndroid(osName, env)

        fun tempCandidate(dir: String): Path? {
            if (dir.isBlank()) return null
            val scopedModDir = if (userName.isNotBlank() && !onAndroid) "$MOD_ID-$userName" else MOD_ID
            return Paths.get(dir).resolve(scopedModDir).resolve(NATIVES_DIR)
        }

        if (onAndroid) {
            // Android linker namespaces (clns) strictly forbid loading .so binaries from external storage.
            // Internal app storage (such as java.io.tmpdir / /data/data/<pkg>/cache) must be prioritized.
            tempCandidate(tempDir)?.let { candidates.add(it) }

            env["TMPDIR"]?.takeIf { it.isNotBlank() }?.let {
                tempCandidate(it)?.let { candidate -> candidates.add(candidate) }
            }
        }

        gameDir?.let {
            candidates.add(it.resolve("cache").resolve(MOD_ID).resolve(NATIVES_DIR))
        }

        if (!onAndroid) {
            osCacheDirectory(osName, env, userHome)?.let {
                candidates.add(it.resolve(MOD_ID).resolve(NATIVES_DIR))
            }
        }

        candidates.add(configDir.resolve("cache").resolve(NATIVES_DIR))

        if (!onAndroid) {
            tempCandidate(tempDir)?.let { candidates.add(it) }
        }

        val filtered = if (onAndroid) {
            candidates.filterNot { isAndroidExternalStorage(it) }
        } else {
            candidates
        }

        return filtered.map { it.toAbsolutePath().normalize() }.distinct()
    }

    internal fun isAndroid(
        osName: String = System.getProperty("os.name").orEmpty(),
        env: Map<String, String> = System.getenv(),
        propertyGetter: (String) -> String? = { System.getProperty(it) },
    ): Boolean {
        if ("android" in osName.lowercase()) return true

        val runtimeName = propertyGetter("java.runtime.name").orEmpty().lowercase()
        val vmName = propertyGetter("java.vm.name").orEmpty().lowercase()
        val vmVendor = propertyGetter("java.vm.vendor").orEmpty().lowercase()
        val vendor = propertyGetter("java.vendor").orEmpty().lowercase()

        if ("android" in runtimeName || "dalvik" in vmName || "art" in vmName || "android" in vmVendor || "android" in vendor) {
            return true
        }

        if (env.containsKey("ANDROID_ROOT") ||
            env.containsKey("ANDROID_DATA") ||
            env.containsKey("ANDROID_ART_ROOT") ||
            env.containsKey("ANDROID_I18N_ROOT") ||
            env.containsKey("ANDROID_TZDATA_ROOT")
        ) {
            return true
        }

        if (Files.exists(Paths.get("/system/build.prop")) ||
            Files.exists(Paths.get("/apex/com.android.runtime")) ||
            Files.exists(Paths.get("/system/bin/linker64")) ||
            Files.exists(Paths.get("/system/bin/linker"))
        ) {
            return true
        }

        return checkProcSelfMapsForAndroid()
    }

    private fun checkProcSelfMapsForAndroid(): Boolean {
        val mapsPath = Paths.get("/proc/self/maps")
        if (!Files.exists(mapsPath)) return false
        return runCatching {
            Files.newBufferedReader(mapsPath).use { reader ->
                reader.lineSequence().any { line ->
                    line.contains("/bionic/libc.so") ||
                        line.contains("/apex/com.android.runtime/") ||
                        line.contains("/system/lib/libc.so") ||
                        line.contains("/system/lib64/libc.so")
                }
            }
        }.getOrDefault(false)
    }

    private val ANDROID_EXTERNAL_STORAGE_PREFIXES = listOf(
        "/storage/",
        "/sdcard",
        "/data/media/",
        "/mnt/sdcard",
        "/mnt/media_rw/",
        "/mnt/obb/",
        "/mnt/user/",
        "/mnt/runtime/",
        "/mnt/expand/",
        "/mnt/pass_through/",
        "/mnt/androidwritable/",
    )

    internal fun resolveRealPath(path: Path): Path {
        // safely resolves symbolic links (symlinks) to find the true physical path where files will actually
        // be created, even when target subdirectories do not exist on disk yet.
        // GPT says this is vulnerable to symlink swapping, which may allow other processes that can swap part
        // of the path to another symlink to redirect the final native library path under its control,
        // and inject code to this process (because `runCatching { current.toRealPath() }.getOrDefault(current)`
        // has fallback, allowing symlink in the "real path" in rare case (what case?), and the later directory
        // creation is not atomic).
        // But: This is only checked on android, any other processes that can do this already hold the same privilege
        // as this process, especially considering android will block library loading on shared storage.
        // Maybe this would be a thing on other platforms? Pre-existing trust-boundary issue.
        // Currently I think that's acceptable.
        var current: Path? = path.toAbsolutePath().normalize()
        val missingSuffixes = mutableListOf<String>()

        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missingSuffixes.add(0, current.fileName?.toString().orEmpty())
            current = current.parent
        }

        val realBase = if (current != null) {
            runCatching { current.toRealPath() }.getOrDefault(current)
        } else {
            path.toAbsolutePath().normalize()
        }

        return missingSuffixes.fold(realBase) { acc, segment ->
            if (segment.isNotEmpty()) acc.resolve(segment) else acc
        }.normalize()
    }

    internal fun isAndroidExternalStorage(path: Path): Boolean {
        val realPathStr = resolveRealPath(path).toString()
        val normalizedPathStr = path.toAbsolutePath().normalize().toString()
        return ANDROID_EXTERNAL_STORAGE_PREFIXES.any { prefix ->
            realPathStr.startsWith(prefix) || normalizedPathStr.startsWith(prefix)
        }
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
        if (isAndroid() && isAndroidExternalStorage(path)) {
            throw IOException("Path resolves to Android external storage which is forbidden for native library loading: $path")
        }

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
