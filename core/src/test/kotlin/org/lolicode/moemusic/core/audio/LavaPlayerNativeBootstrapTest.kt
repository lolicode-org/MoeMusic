package org.lolicode.moemusic.core.audio
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
}
