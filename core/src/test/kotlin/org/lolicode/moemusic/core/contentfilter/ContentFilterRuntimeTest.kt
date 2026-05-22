package org.lolicode.moemusic.core.contentfilter

import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.core.config.MoeMusicConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ContentFilterRuntimeTest {

    @Test
    fun exactTrackRuleBlocksTrackAndDirectSelectionId() {
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    exactTrackRules = listOf(ExactTrackFilterRule(sourceId = "netease", trackId = "123")),
                ),
            )
        )

        val track = TrackInfo(
            id = "123",
            title = "Song",
            artists = listOf("Artist").toArtistInfos(),
            durationMs = 180_000,
            sourceId = "netease",
        )
        val selection = SelectionEntry(
            selectionId = "123",
            title = "Song",
            artists = listOf("Artist").toArtistInfos(),
            durationMs = 180_000,
            sourceId = "netease",
            kind = SelectionEntryKind.TRACK,
        )

        assertIs<FilterVerdict.Reject>(ContentFilterRuntime.trackFilterVerdict(track))
        assertIs<FilterVerdict.Reject>(ContentFilterRuntime.selectionFilterVerdict(selection))
    }

    @Test
    fun exactArtistRuleUsesStableArtistInfo() {
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    exactArtistRules = listOf(ExactArtistFilterRule(sourceId = "spotify", artistId = "artist-42")),
                ),
            )
        )

        val track = TrackInfo(
            id = "song-1",
            title = "Song",
            artists = listOf(ArtistInfo(id = "artist-42", name = "Original Artist")),
            durationMs = 200_000,
            sourceId = "spotify",
        )

        val verdict = ContentFilterRuntime.trackFilterVerdict(track)
        val reason = assertIs<FilterVerdict.Reject>(verdict).reason
        assertIs<LocalizedText.Key>(reason)
        assertEquals("error.moemusic.content_filter.artist_blocked", reason.key)
    }

    @Test
    fun textRuleMatchesCommonFields() {
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    textRules = listOf(
                        ContentFilterTextRule(
                            pattern = "spoiler",
                            mode = ContentFilterTextRuleMode.SUBSTRING,
                            scope = ContentFilterTextRuleScope.TITLE,
                            ignoreCase = true,
                        )
                    ),
                ),
            )
        )

        val blocked = TrackInfo(
            id = "song-2",
            title = "Big Spoiler Theme",
            artists = listOf("Artist").toArtistInfos(),
            durationMs = 150_000,
            sourceId = "youtube",
        )
        val allowed = blocked.copy(title = "Opening Theme")

        assertIs<FilterVerdict.Reject>(ContentFilterRuntime.trackFilterVerdict(blocked))
        assertNull(ContentFilterRuntime.trackBlockReason(allowed))
    }

    @Test
    fun miscRuleMatchesPluginProvidedValuesOnly() {
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    textRules = listOf(
                        ContentFilterTextRule(
                            pattern = "spoiler",
                            mode = ContentFilterTextRuleMode.SUBSTRING,
                            scope = ContentFilterTextRuleScope.MISC,
                            ignoreCase = true,
                        )
                    ),
                ),
            )
        )

        assertIs<FilterVerdict.Reject>(ContentFilterRuntime.miscFilterVerdict(listOf("episode spoiler notes")))
        assertIs<FilterVerdict.Reject>(ContentFilterRuntime.textFilterVerdict(ContentFilterTextRuleScope.MISC, listOf("episode spoiler notes")))
        assertNull(
            ContentFilterRuntime.trackBlockReason(
                TrackInfo(
                    id = "song-3",
                    title = "Opening Theme",
                    artists = listOf("Artist").toArtistInfos(),
                    durationMs = 150_000,
                    sourceId = "youtube",
                )
            )
        )
    }

    @Test
    fun allRuleAlsoMatchesScopedTextValues() {
        ContentFilterRuntime.applyConfig(
            MoeMusicConfig(
                contentFilter = ContentFilterRules(
                    enabled = true,
                    textRules = listOf(
                        ContentFilterTextRule(
                            pattern = "spoiler",
                            mode = ContentFilterTextRuleMode.SUBSTRING,
                            scope = ContentFilterTextRuleScope.ALL,
                            ignoreCase = true,
                        )
                    ),
                ),
            )
        )

        assertIs<FilterVerdict.Reject>(ContentFilterRuntime.miscFilterVerdict(listOf("description with spoiler")))
        assertIs<FilterVerdict.Reject>(ContentFilterRuntime.textFilterVerdict(ContentFilterTextRuleScope.MISC, listOf("description with spoiler")))
        assertIs<FilterVerdict.Reject>(ContentFilterRuntime.textFilterVerdict(ContentFilterTextRuleScope.QUERY, listOf("spoiler opening")))
    }
}
