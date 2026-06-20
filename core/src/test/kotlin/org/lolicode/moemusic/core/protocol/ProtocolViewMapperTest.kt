package org.lolicode.moemusic.core.protocol

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.ContentFilterRules
import org.lolicode.moemusic.api.model.ExactTrackFilterRule
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.SelectionEntryKind
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.toArtistInfos
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolViewMapperTest {

    @BeforeTest
    fun resetBeforeTest() {
        ContentFilterRuntime.applyConfig(MoeMusicConfig())
    }

    @AfterTest
    fun resetAfterTest() {
        ContentFilterRuntime.applyConfig(MoeMusicConfig())
    }

    @Test
    fun `track mapping masks filter detail for non managers`() {
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    exactTrackRules = listOf(ExactTrackFilterRule(sourceId = "netease", trackId = "123")),
                ),
            )
        )
        val track = TrackInfo(id = "123", title = "Blocked Song", artists = listOf("Artist").toArtistInfos(), durationMs = 180_000) { sourceId = "netease" }

        val masked = ProtocolViewMapper.trackToClientProto(track, canBypass = false, canSeeDetail = false, ::render)
        val detailed = ProtocolViewMapper.trackToClientProto(track, canBypass = false, canSeeDetail = true, ::render)

        assertEquals("error.moemusic.content_filter.managed", masked.unavailable_reason)
        assertEquals("error.moemusic.content_filter.track_blocked", detailed.unavailable_reason)
    }

    @Test
    fun `selection mapping keeps inherent unavailable reason for bypass viewers`() {
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    exactTrackRules = listOf(ExactTrackFilterRule(sourceId = "netease", trackId = "123")),
                ),
            )
        )
        val entry = SelectionEntry(selectionId = "123", title = "Blocked Song", artists = listOf("Artist").toArtistInfos(), durationMs = 180_000) { sourceId = "netease"; unavailableReason = LocalizedText.key("error.moemusic.track_unavailable"); kind = SelectionEntryKind.TRACK }

        val proto = ProtocolViewMapper.selectionToClientProto(
            entry = entry,
            canBypass = true,
            canSeeDetail = true,
            render = ::render,
        )

        assertEquals("error.moemusic.track_unavailable", proto.unavailable_reason)
    }

    private fun render(text: LocalizedText): String = when (text) {
        is LocalizedText.Key -> text.key
        is LocalizedText.Plain -> text.text
    }
}
