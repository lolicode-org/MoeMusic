package org.lolicode.moemusic.core.config

import org.lolicode.moemusic.api.model.LoudnessInfo
import org.lolicode.moemusic.api.model.PeakInfo
import org.lolicode.moemusic.api.model.PeakKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MoeMusicConfigTest {

    @Test
    fun `config normalization clamps and normalizes values`() {
        val normalized = MoeMusicConfig(
            defaultLanguage = " ZH-CN ",
            voteRequiredPercent = 999,
            permissions = PermissionDefaultsConfig(
                submit = 99,
                queueControl = 99,
                vote = 99,
                playbackControl = -1,
                configReload = 99,
                systemInfo = -1,
                autoplayRefresh = -1,
                durationPolicyBypass = 99,
                rateLimitBypass = -1,
            ),
            media = MediaPolicyConfig(
                allowLocalFiles = true,
                firewall = MediaFirewallConfig(
                    hosts = listOf(" .Example.com ", "", "cdn.example.com", "example.com"),
                ),
                maxPlayerTrackDurationSeconds = 999_999_999,
                maxSearchResultsPerPage = -5,
                rateLimit = RequestRateLimitConfig(
                    windowSeconds = 0,
                    searchRequests = -1,
                    submitRequests = 99_999,
                ),
            ),
            client = ClientConfig(
                playbackEnabled = false,
                blockVanillaMusic = false,
                blockRecords = true,
                globalInstancePlaybackLock = false,
                disabledServers = listOf(" Server:Example.Com ", "", "singleplayer:World", "server:example.com"),
                volume = 250,
                loudnessNormalization = LoudnessNormalizationConfig(
                    mode = LoudnessNormalizationMode.ATTENUATE_ONLY,
                    targetLufs = Double.POSITIVE_INFINITY,
                ),
                joinShortcutTipShown = true,
                coverArt = CoverArtConfig(
                    maxDownloadMebibytes = 0,
                    maxSourceDimension = 12,
                    maxSourcePixels = Long.MAX_VALUE,
                    maxDecodeDownscaleFactor = 0,
                    maxTextureSize = 9_999,
                ),
                nowPlayingHud = NowPlayingHudConfig(
                    offsetX = -10,
                    offsetY = 20_000,
                    verticalSize = 8,
                    textMaxWidth = 2_000,
                    textColorArgb = "#abcdef12",
                    secondaryTextColorArgb = " bad ",
                    backgroundColorArgb = " 7f102030 ",
                    progressBarColorArgb = "12345678",
                    pausedProgressBarColorArgb = "",
                    progressBarBackgroundColorArgb = "#ABCDEF01",
                    recordRingColorArgb = "nothex",
                ),
            ),
        ).normalized()

        assertEquals("zh_cn", normalized.defaultLanguage)
        assertEquals(100, normalized.voteRequiredPercent)
        assertEquals(4, normalized.permissions.submit)
        assertEquals(4, normalized.permissions.queueControl)
        assertEquals(4, normalized.permissions.vote)
        assertEquals(0, normalized.permissions.playbackControl)
        assertEquals(4, normalized.permissions.configReload)
        assertEquals(0, normalized.permissions.systemInfo)
        assertEquals(0, normalized.permissions.autoplayRefresh)
        assertEquals(4, normalized.permissions.durationPolicyBypass)
        assertEquals(0, normalized.permissions.rateLimitBypass)
        assertEquals(604_800, normalized.media.maxPlayerTrackDurationSeconds)
        assertEquals(1, normalized.media.maxSearchResultsPerPage)
        assertEquals(1, normalized.media.rateLimit.windowSeconds)
        assertEquals(0, normalized.media.rateLimit.searchRequests)
        assertEquals(1_000, normalized.media.rateLimit.submitRequests)
        assertEquals(true, normalized.media.allowLocalFiles)
        assertEquals(listOf("cdn.example.com", "example.com"), normalized.media.firewall.hosts)
        assertEquals(false, normalized.client.playbackEnabled)
        assertEquals(false, normalized.client.blockVanillaMusic)
        assertEquals(true, normalized.client.blockRecords)
        assertEquals(false, normalized.client.globalInstancePlaybackLock)
        assertEquals(listOf("server:example.com", "singleplayer:world"), normalized.client.disabledServers)
        assertEquals(100, normalized.client.volume)
        assertEquals(LoudnessNormalizationMode.ATTENUATE_ONLY, normalized.client.loudnessNormalization.mode)
        assertEquals(-14.0, normalized.client.loudnessNormalization.targetLufs)
        assertEquals(true, normalized.client.joinShortcutTipShown)
        assertEquals(1, normalized.client.coverArt.maxDownloadMebibytes)
        assertEquals(64, normalized.client.coverArt.maxSourceDimension)
        assertEquals(268_435_456L, normalized.client.coverArt.maxSourcePixels)
        assertEquals(1, normalized.client.coverArt.maxDecodeDownscaleFactor)
        assertEquals(2_048, normalized.client.coverArt.maxTextureSize)
        assertEquals(0, normalized.client.nowPlayingHud.offsetX)
        assertEquals(10_000, normalized.client.nowPlayingHud.offsetY)
        assertEquals(16, normalized.client.nowPlayingHud.verticalSize)
        assertEquals(1_024, normalized.client.nowPlayingHud.textMaxWidth)
        assertEquals("ABCDEF12", normalized.client.nowPlayingHud.textColorArgb)
        assertEquals("FFCCCCCC", normalized.client.nowPlayingHud.secondaryTextColorArgb)
        assertEquals("7F102030", normalized.client.nowPlayingHud.backgroundColorArgb)
        assertEquals("12345678", normalized.client.nowPlayingHud.progressBarColorArgb)
        assertEquals("FFF4D35E", normalized.client.nowPlayingHud.pausedProgressBarColorArgb)
        assertEquals("ABCDEF01", normalized.client.nowPlayingHud.progressBarBackgroundColorArgb)
        assertEquals("FF000000", normalized.client.nowPlayingHud.recordRingColorArgb)
    }

    @Test
    fun `blank or invalid default language falls back to en_us`() {
        assertEquals("en_us", MoeMusicConfig(defaultLanguage = "  ").normalized().defaultLanguage)
        assertEquals("en_us", MoeMusicConfig(defaultLanguage = "../bad").normalized().defaultLanguage)
    }

    @Test
    fun `loudness normalization attenuates loud tracks and does not boost in attenuate only mode`() {
        val config = LoudnessNormalizationConfig(
            mode = LoudnessNormalizationMode.ATTENUATE_ONLY,
            targetLufs = -14.0,
        )
        val loudTrack = LoudnessInfo { integratedLufs = -8.0 }
        val quietTrack = LoudnessInfo { integratedLufs = -20.0 }

        assertEquals(1.0f, config.gainForTrack(quietTrack))
        assertEquals(1.0f, config.gainForTrack(null))
        assertEquals(1.0f, config.copy(mode = LoudnessNormalizationMode.OFF).gainForTrack(loudTrack))
        assertEquals(0.5011872f, config.gainForTrack(loudTrack), 0.000001f)
    }

    @Test
    fun `conservative boost requires valid peak data and respects caps`() {
        val config = LoudnessNormalizationConfig(
            mode = LoudnessNormalizationMode.CONSERVATIVE_BOOST,
            targetLufs = -14.0,
        )

        val withTruePeak = LoudnessInfo {
            integratedLufs = -20.0
            peak = PeakInfo(0.5) { kind = PeakKind.TRUE }
        }
        val withSamplePeak = LoudnessInfo {
            integratedLufs = -20.0
            peak = PeakInfo(0.5) { kind = PeakKind.SAMPLE }
        }
        val withoutPeak = LoudnessInfo { integratedLufs = -20.0 }
        val invalidPeak = LoudnessInfo {
            integratedLufs = -20.0
            peak = PeakInfo(0.0) { kind = PeakKind.TRUE }
        }

        assertEquals(1.7825019f, config.gainForTrack(withTruePeak), 0.000001f)
        assertEquals(1.5886564f, config.gainForTrack(withSamplePeak), 0.000001f)
        assertEquals(1.0f, config.gainForTrack(withoutPeak))
        assertEquals(1.0f, config.gainForTrack(invalidPeak))
    }
}
