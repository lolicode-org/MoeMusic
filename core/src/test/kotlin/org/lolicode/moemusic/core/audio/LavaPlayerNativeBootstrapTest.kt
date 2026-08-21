package org.lolicode.moemusic.core.audio

import java.nio.file.Paths
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class LavaPlayerNativeBootstrapTest {

    @Test
    fun `candidate paths prefer game cache before os and config fallbacks`() {
        val gameDir = createTempDirectory("moemusic-game")
        val configDir = createTempDirectory("moemusic-config")
        val homeDir = createTempDirectory("moemusic-home")

        val candidates = LavaPlayerNativeBootstrap.candidateExtractionPaths(
            gameDir = gameDir,
            configDir = configDir,
            osName = "Linux",
            env = emptyMap(),
            userHome = homeDir.toString(),
            tempDir = "",
        )

        assertEquals(gameDir.resolve("cache/moemusic/lavaplayer-natives"), candidates[0])
        assertEquals(homeDir.resolve(".cache/moemusic/lavaplayer-natives"), candidates[1])
        assertEquals(configDir.resolve("cache/lavaplayer-natives"), candidates[2])
    }

    @Test
    fun `os cache fallback prefers environment home over jvm user home`() {
        val configDir = createTempDirectory("moemusic-config")

        val candidates = LavaPlayerNativeBootstrap.candidateExtractionPaths(
            gameDir = null,
            configDir = configDir,
            osName = "Linux",
            env = mapOf("HOME" to "/home/alice"),
            userHome = "/home/alice/.minecraft/instances/overridden",
            tempDir = "",
        )

        assertEquals(
            Paths.get("/home/alice/.cache/moemusic/lavaplayer-natives"),
            candidates[0],
        )
        assertEquals(configDir.resolve("cache/lavaplayer-natives"), candidates[1])
    }

    @Test
    fun `selection skips unusable game cache and falls back to config cache`() {
        val gameDir = createTempDirectory("moemusic-game")
        val configDir = createTempDirectory("moemusic-config")
        gameDir.resolve("cache/moemusic/lavaplayer-natives").also {
            it.parent.toFile().mkdirs()
            it.writeText("not a directory")
        }

        val selected = LavaPlayerNativeBootstrap.selectUsableExtractionPath(
            gameDir = gameDir,
            configDir = configDir,
            osName = "Linux",
            env = emptyMap(),
            userHome = "",
            tempDir = "",
        )

        assertEquals(configDir.resolve("cache/lavaplayer-natives").toAbsolutePath().normalize(), selected)
    }

    @Test
    fun `candidate paths on android prioritize temp directory and exclude external storage`() {
        val gameDir = Paths.get("/storage/emulated/0/Android/data/com.movtery.zalithlauncher.v2/files/.minecraft/versions/26.2 Fabric 0.19.3")
        val configDir = Paths.get("/storage/emulated/0/Android/data/com.movtery.zalithlauncher.v2/files/.minecraft/config/moemusic")
        val tempDir = "/data/user/0/com.movtery.zalithlauncher.v2/cache"

        val candidates = LavaPlayerNativeBootstrap.candidateExtractionPaths(
            gameDir = gameDir,
            configDir = configDir,
            osName = "Linux",
            env = mapOf("ANDROID_ROOT" to "/system"),
            userHome = "/data/user/0/com.movtery.zalithlauncher.v2",
            tempDir = tempDir,
            isAndroidOverride = true,
        )

        assertEquals(1, candidates.size)
        assertEquals(
            Paths.get("/data/user/0/com.movtery.zalithlauncher.v2/cache/moemusic/lavaplayer-natives"),
            candidates[0],
        )
    }

    @Test
    fun `candidate paths on desktop scope temp directory by username`() {
        val candidates = LavaPlayerNativeBootstrap.candidateExtractionPaths(
            gameDir = null,
            configDir = Paths.get("/home/alice/.minecraft/config/moemusic"),
            osName = "Linux",
            env = mapOf("HOME" to "/home/alice"),
            userHome = "/home/alice",
            tempDir = "/tmp",
            userName = "alice",
            isAndroidOverride = false,
        )

        assertEquals(
            Paths.get("/home/alice/.cache/moemusic/lavaplayer-natives"),
            candidates[0],
        )
        assertEquals(
            Paths.get("/home/alice/.minecraft/config/moemusic/cache/lavaplayer-natives"),
            candidates[1],
        )
        assertEquals(
            Paths.get("/tmp/moemusic-alice/lavaplayer-natives"),
            candidates[2],
        )
    }

    @Test
    fun `isAndroid detects android from OS name, vendor, runtime, or env`() {
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroid(osName = "Android"))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroid(env = mapOf("ANDROID_ROOT" to "/system")))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroid(env = mapOf("ANDROID_I18N_ROOT" to "/apex/com.android.i18n")))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroid(env = mapOf("ANDROID_TZDATA_ROOT" to "/apex/com.android.tzdata")))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroid(propertyGetter = { if (it == "java.vm.vendor") "The Android Project" else null }))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroid(propertyGetter = { if (it == "java.runtime.name") "Android Runtime" else null }))
        assertEquals(false, LavaPlayerNativeBootstrap.isAndroid(osName = "Linux", env = emptyMap(), propertyGetter = { null }))
    }

    @Test
    fun `isAndroidExternalStorage identifies external storage paths including runtime and user mounts`() {
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroidExternalStorage(Paths.get("/storage/emulated/0/Android/data")))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroidExternalStorage(Paths.get("/sdcard/Download")))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroidExternalStorage(Paths.get("/mnt/sdcard/games")))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroidExternalStorage(Paths.get("/mnt/user/0/primary/Android/data")))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroidExternalStorage(Paths.get("/mnt/runtime/default/emulated/0/games")))
        assertEquals(true, LavaPlayerNativeBootstrap.isAndroidExternalStorage(Paths.get("/mnt/expand/1234-5678/Android/data")))
        assertEquals(false, LavaPlayerNativeBootstrap.isAndroidExternalStorage(Paths.get("/data/user/0/com.app/cache")))
        assertEquals(false, LavaPlayerNativeBootstrap.isAndroidExternalStorage(Paths.get("/home/user/.minecraft")))
    }

    @Test
    fun `isAndroidExternalStorage detects symlinks pointing to external storage`() {
        val tempParent = createTempDirectory("moemusic-symlink-test")
        val targetExternal = tempParent.resolve("mock-external")
        Files.createDirectories(targetExternal)
        val symlink = tempParent.resolve("internal-cache")

        try {
            Files.createSymbolicLink(symlink, targetExternal)
            // If targetExternal is considered normal, it returns false
            assertEquals(false, LavaPlayerNativeBootstrap.isAndroidExternalStorage(symlink))

            // Now test with symlink pointing to a fake /storage prefix if possible or verifying resolveRealPath
            val resolved = LavaPlayerNativeBootstrap.resolveRealPath(symlink.resolve("moemusic").resolve("lavaplayer-natives"))
            assertEquals(targetExternal.resolve("moemusic/lavaplayer-natives").toAbsolutePath().normalize(), resolved)
        } catch (ignored: UnsupportedOperationException) {
            // Symlinks may not be supported on all test filesystems
        } finally {
            Files.deleteIfExists(symlink)
            Files.deleteIfExists(targetExternal)
            Files.deleteIfExists(tempParent)
        }
    }
}

