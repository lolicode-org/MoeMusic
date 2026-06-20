package org.lolicode.moemusic.clientcore.hud

import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.api.model.TrackContext
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.toArtistInfos
import org.lolicode.moemusic.core.config.HudAnchor
import org.lolicode.moemusic.core.config.HudCoverSide
import org.lolicode.moemusic.core.config.HudProgressBarPosition
import org.lolicode.moemusic.core.config.HudTextAlignment
import org.lolicode.moemusic.core.config.NowPlayingHudConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NowPlayingHudModelTest {

    @Test
    fun `metadata lines and progress derive from track state`() {
        val ctx = trackContext(durationMs = 180_000)
        val config = NowPlayingHudConfig(
            showTitle = true,
            showArtist = true,
            showAlbum = true,
            showTime = true,
            showLyrics = true,
        )

        val lines = NowPlayingHudModel.metadataLines(ctx, config, positionMs = 65_000)

        assertEquals(listOf("Song Title", "Artist Name", "Album Name", "1:05 / 3:00"), lines.map { it.text })
        assertEquals(0xFFFFFFFF.toInt(), lines.first().color)
        assertEquals(0xFFCCCCCC.toInt(), lines.last().color)
        assertEquals(1f, NowPlayingHudModel.computeProgress(ctx, positionMs = 250_000))
        assertNull(NowPlayingHudModel.lyricLine("ignored", "FFFFFFFF", showLyrics = false))
        assertEquals("", NowPlayingHudModel.lyricLine(null, "FFFFFFFF", showLyrics = true)?.text)
    }

    @Test
    fun `layout respects anchor cover side and progress placement`() {
        val config = NowPlayingHudConfig(
            anchor = HudAnchor.BOTTOM_RIGHT,
            offsetX = 10,
            offsetY = 20,
            verticalSize = 60,
            textMaxWidth = 160,
            coverSide = HudCoverSide.RIGHT,
            textAlignment = HudTextAlignment.RIGHT,
            progressBarPosition = HudProgressBarPosition.BOTTOM,
            showCover = true,
            showProgressBar = true,
        )
        val metadata = listOf(
            NowPlayingHudModel.HudLine("Title", 1),
            NowPlayingHudModel.HudLine("Artist", 2),
        )

        val layout = assertNotNull(
            NowPlayingHudModel.computeLayout(
                guiWidth = 400,
                guiHeight = 300,
                fontLineHeight = 10,
                config = config,
                metadataLines = metadata,
                primaryLyric = null,
                secondaryLyric = null,
            )
        )

        assertEquals(152, layout.panelX)
        assertEquals(199, layout.panelY)
        assertEquals(320, layout.coverX)
        assertEquals(60, layout.coverSize)
        assertEquals(156, layout.textX)
        assertEquals(267, layout.progressY)
        assertEquals(29, layout.scaledFontHeight)
        assertEquals(2, layout.textLineGap)
        assertEquals(60, layout.textBlockHeight)
    }

    private fun trackContext(durationMs: Long): TrackContext =
        TrackContext(
            track = TrackInfo(id = "track-1", title = "Song Title", artists = listOf("Artist Name").toArtistInfos(), durationMs = durationMs) { sourceId = "http"; album = "Album Name" },
            playback = PlaybackResource(url = "https://example.com/audio.mp3"),
            state = PlaybackState.Playing(positionMs = 65_000),
        )
}
