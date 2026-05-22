package org.lolicode.moemusic.core.config

import org.lolicode.moemusic.api.model.ContentFilterRules
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModConfigManagerTest {

    @Test
    fun `update client preserves latest shared config sections`() {
        ModConfigManager.load(createTempDirectory("moemusic-config-update-client"))

        ModConfigManager.update {
            it.copy(
                defaultSourceId = "alpha",
                voteRequiredPercent = 75,
                contentFilter = ContentFilterRules(enabled = false),
            )
        }

        ModConfigManager.updateClient { client ->
            client.copy(
                playbackEnabled = false,
                blockVanillaMusic = false,
                blockRecords = true,
                globalInstancePlaybackLock = false,
                volume = 25,
                joinShortcutTipShown = true,
            )
        }

        val saved = ModConfigManager.config
        assertEquals("alpha", saved.defaultSourceId)
        assertEquals(75, saved.voteRequiredPercent)
        assertFalse(saved.contentFilter.enabled)
        assertFalse(saved.client.playbackEnabled)
        assertFalse(saved.client.blockVanillaMusic)
        assertTrue(saved.client.blockRecords)
        assertFalse(saved.client.globalInstancePlaybackLock)
        assertEquals(25, saved.client.volume)
        assertTrue(saved.client.joinShortcutTipShown)
    }

    @Test
    fun `update content filter preserves latest client config fields`() {
        ModConfigManager.load(createTempDirectory("moemusic-config-update-filter"))

        ModConfigManager.updateClient { client ->
            client.copy(
                playbackEnabled = false,
                blockVanillaMusic = false,
                blockRecords = true,
                globalInstancePlaybackLock = false,
                volume = 60,
                joinShortcutTipShown = true,
            )
        }

        ModConfigManager.updateContentFilter {
            it.copy(enabled = false)
        }

        val saved = ModConfigManager.config
        assertFalse(saved.contentFilter.enabled)
        assertFalse(saved.client.playbackEnabled)
        assertFalse(saved.client.blockVanillaMusic)
        assertTrue(saved.client.blockRecords)
        assertFalse(saved.client.globalInstancePlaybackLock)
        assertEquals(60, saved.client.volume)
        assertTrue(saved.client.joinShortcutTipShown)
    }

    @Test
    fun `volume is persisted as integer percent`() {
        val configDir = createTempDirectory("moemusic-config-volume-format")
        ModConfigManager.load(configDir)

        ModConfigManager.updateClient { client ->
            client.copy(volume = 35)
        }

        val savedText = configDir.resolve("moemusic.toml").readText()
        assertTrue("volume = 35" in savedText)
        assertFalse("volume = 0.35" in savedText)
    }

    @Test
    fun `default language is persisted in generated config`() {
        val configDir = createTempDirectory("moemusic-config-default-language")
        ModConfigManager.load(configDir)

        val savedText = configDir.resolve("moemusic.toml").readText()
        assertTrue("default_language = \"en_us\"" in savedText)
    }

    @Test
    fun `reload keeps current config when disk file is invalid`() {
        val configDir = createTempDirectory("moemusic-config-reload-invalid")
        ModConfigManager.load(configDir)
        ModConfigManager.update {
            it.copy(defaultSourceId = "alpha", voteRequiredPercent = 75)
        }

        val configFile = configDir.resolve("moemusic.toml")
        configFile.writeText("default_source_id = [")

        assertFailsWith<IllegalStateException> {
            ModConfigManager.reload(configDir)
        }

        assertEquals("alpha", ModConfigManager.config.defaultSourceId)
        assertEquals(75, ModConfigManager.config.voteRequiredPercent)
        assertEquals("default_source_id = [", configFile.readText())
    }
}
