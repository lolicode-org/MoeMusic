package org.lolicode.moemusic.core.contentfilter

import org.lolicode.moemusic.api.event.OnContentFilterArtistRuleChanged
import org.lolicode.moemusic.api.event.OnContentFilterRulesApplied
import org.lolicode.moemusic.api.event.OnContentFilterTrackRuleChanged
import org.lolicode.moemusic.api.model.ContentFilterRuleAction
import org.lolicode.moemusic.api.model.ContentFilterRules
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.plugin.PluginManager
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ContentFilterRuleEditorTest {

    init {
        ModConfigManager.load(Files.createTempDirectory("moemusic-content-filter-editor-test"))
    }

    @Test
    fun `updateTrackRule emits track change and rules applied events`() {
        PluginManager.eventBus.clear()
        var changeEvent: OnContentFilterTrackRuleChanged? = null
        var appliedEvent: OnContentFilterRulesApplied? = null
        PluginManager.eventBus.subscribe(OnContentFilterTrackRuleChanged::class.java) { changeEvent = it }
        PluginManager.eventBus.subscribe(OnContentFilterRulesApplied::class.java) { appliedEvent = it }

        val result = ContentFilterRuleEditor.updateTrackRule(
            sourceId = "ncm",
            trackId = "123",
            action = ContentFilterRuleAction.BAN,
            note = "test note",
        )

        assertEquals(true, result.changed)
        assertEquals(true, result.nowBlocked)
        assertEquals(1, result.affectedCount)
        val change = assertNotNull(changeEvent)
        assertEquals("ncm", change.sourceId)
        assertEquals("123", change.trackId)
        assertEquals(ContentFilterRuleAction.BAN, change.action)
        assertEquals("test note", change.note)
        assertEquals(true, change.changed)
        assertEquals(true, change.nowBlocked)
        assertEquals(1, change.affectedCount)
        assertEquals("123", assertNotNull(appliedEvent).rules.exactTrackRules.single().trackId)
    }

    @Test
    fun `updateArtistRules emits artist change and rules applied events`() {
        PluginManager.eventBus.clear()
        var changeEvent: OnContentFilterArtistRuleChanged? = null
        var appliedEvent: OnContentFilterRulesApplied? = null
        PluginManager.eventBus.subscribe(OnContentFilterArtistRuleChanged::class.java) { changeEvent = it }
        PluginManager.eventBus.subscribe(OnContentFilterRulesApplied::class.java) { appliedEvent = it }

        val result = ContentFilterRuleEditor.updateArtistRules(
            sourceId = "yt",
            artistIds = listOf("artist-1"),
            action = ContentFilterRuleAction.BAN,
            note = "artist note",
        )

        assertEquals(true, result.changed)
        assertEquals(true, result.nowBlocked)
        val change = assertNotNull(changeEvent)
        assertEquals("yt", change.sourceId)
        assertEquals(listOf("artist-1"), change.artistIds)
        assertEquals(ContentFilterRuleAction.BAN, change.action)
        assertEquals("artist note", change.note)
        assertEquals(true, change.changed)
        assertEquals(true, change.nowBlocked)
        assertEquals(1, assertNotNull(appliedEvent).rules.exactArtistRules.size)
    }

    @Test
    fun `reloadFromDisk only replaces filter rules in memory`() {
        val configDir = Files.createTempDirectory("moemusic-content-filter-reload-test")
        ModConfigManager.load(configDir)
        ModConfigManager.save(
            MoeMusicConfig(
                defaultSourceId = "runtime-alpha",
                contentFilter = ContentFilterRules(enabled = false),
            )
        )

        configDir.resolve("moemusic.toml").writeText(
            """
            default_source_id = "disk-beta"

            [content_filter]
            enabled = true
            """.trimIndent(),
        )

        ContentFilterRuleEditor.reloadFromDisk(configDir)

        assertEquals("runtime-alpha", ModConfigManager.config.defaultSourceId)
        assertFalse("default_source_id = \"runtime-alpha\"" in configDir.resolve("moemusic.toml").toFile().readText())
        assertEquals(true, ModConfigManager.config.contentFilter.enabled)
    }
}
