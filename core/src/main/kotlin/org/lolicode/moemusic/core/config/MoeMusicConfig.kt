package org.lolicode.moemusic.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.model.ContentFilterRules
import org.lolicode.moemusic.api.model.LoudnessInfo
import org.lolicode.moemusic.api.model.PeakKind
import org.lolicode.moemusic.api.model.normalizedOrNull
import org.lolicode.moemusic.core.contentfilter.normalized
import org.lolicode.moemusic.core.media.MediaHostListMode
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

const val DEFAULT_SERVER_LANGUAGE: String = "en_us"

/**
 * Root configuration for the MoeMusic mod.
 *
 * Serialized to / deserialized from `<configDir>/moemusic.toml` using ktoml.
 * Each nested object maps to a TOML table with the corresponding [SerialName].
 */
@Serializable
data class MoeMusicConfig(
    @SerialName("default_source_id")
    val defaultSourceId: String = "",

    /**
     * Server-side fallback language used when no client locale is known, such as the server
     * console or users that have not completed the MoeMusic client handshake.
     *
     * The value is normalized to Minecraft's lower-case underscore form and validated against
     * loaded lang bundles once plugin resources are available.
     */
    @SerialName("default_language")
    val defaultLanguage: String = DEFAULT_SERVER_LANGUAGE,

    @SerialName("autoplay")
    val autoplay: AutoplayConfig = AutoplayConfig(),

    @SerialName("vote_required_percent")
    val voteRequiredPercent: Int = 51,

    @SerialName("permissions")
    val permissions: PermissionDefaultsConfig = PermissionDefaultsConfig(),

    @SerialName("content_filter")
    val contentFilter: ContentFilterRules = ContentFilterRules(),

    @SerialName("media")
    val media: MediaPolicyConfig = MediaPolicyConfig(),

    @SerialName("client")
    val client: ClientConfig = ClientConfig(),
)

/**
 * Default fallback permission levels for MoeMusic actions.
 *
 * These levels are only used when no advanced permission provider grants or denies the
 * associated permission node. Singleplayer host and console command sources are always allowed.
 */
@Serializable
data class PermissionDefaultsConfig(
    val submit: Int = 0,

    @SerialName("source_http_submit")
    val sourceHttpSubmit: Int = 4,

    @SerialName("submit_skip_autoplay")
    val submitSkipAutoplay: Int = 0,

    @SerialName("queue_control")
    val queueControl: Int = 1,

    @SerialName("vote")
    val vote: Int = 0,

    @SerialName("playback_control")
    val playbackControl: Int = 1,

    @SerialName("queue_view")
    val queueView: Int = 0,

    val search: Int = 0,

    @SerialName("content_filter_manage")
    val contentFilterManage: Int = 2,

    @SerialName("config_reload")
    val configReload: Int = 4,

    @SerialName("system_info")
    val systemInfo: Int = 4,

    @SerialName("autoplay_refresh")
    val autoplayRefresh: Int = 1,

    @SerialName("content_filter_bypass")
    val contentFilterBypass: Int = 1,

    @SerialName("duration_policy_bypass")
    val durationPolicyBypass: Int = 2,

    @SerialName("rate_limit_bypass")
    val rateLimitBypass: Int = 2,
) {
    fun normalized(): PermissionDefaultsConfig = copy(
        submit = submit.coerceIn(MIN_LEVEL, MAX_LEVEL),
        sourceHttpSubmit = sourceHttpSubmit.coerceIn(MIN_LEVEL, MAX_LEVEL),
        submitSkipAutoplay = submitSkipAutoplay.coerceIn(MIN_LEVEL, MAX_LEVEL),
        queueControl = queueControl.coerceIn(MIN_LEVEL, MAX_LEVEL),
        vote = vote.coerceIn(MIN_LEVEL, MAX_LEVEL),
        playbackControl = playbackControl.coerceIn(MIN_LEVEL, MAX_LEVEL),
        queueView = queueView.coerceIn(MIN_LEVEL, MAX_LEVEL),
        search = search.coerceIn(MIN_LEVEL, MAX_LEVEL),
        contentFilterManage = contentFilterManage.coerceIn(MIN_LEVEL, MAX_LEVEL),
        configReload = configReload.coerceIn(MIN_LEVEL, MAX_LEVEL),
        systemInfo = systemInfo.coerceIn(MIN_LEVEL, MAX_LEVEL),
        autoplayRefresh = autoplayRefresh.coerceIn(MIN_LEVEL, MAX_LEVEL),
        contentFilterBypass = contentFilterBypass.coerceIn(MIN_LEVEL, MAX_LEVEL),
        durationPolicyBypass = durationPolicyBypass.coerceIn(MIN_LEVEL, MAX_LEVEL),
        rateLimitBypass = rateLimitBypass.coerceIn(MIN_LEVEL, MAX_LEVEL),
    )

    private companion object {
        private const val MIN_LEVEL: Int = 0
        private const val MAX_LEVEL: Int = 4
    }
}

fun MoeMusicConfig.normalized(): MoeMusicConfig = copy(
    defaultSourceId = defaultSourceId.trim(),
    defaultLanguage = normalizeLanguageId(defaultLanguage),
    voteRequiredPercent = voteRequiredPercent.coerceIn(1, 100),
    permissions = permissions.normalized(),
    contentFilter = contentFilter.normalized(),
    media = media.normalized(),
    client = client.normalized(),
)

fun normalizeLanguageId(language: String): String {
    val normalized = language.trim().lowercase(Locale.ROOT).replace('-', '_')
    return normalized.takeIf { it.isNotBlank() && LANGUAGE_ID_PATTERN.matches(it) } ?: DEFAULT_SERVER_LANGUAGE
}

private val LANGUAGE_ID_PATTERN = Regex("^[a-z0-9_]+$")

@Serializable
data class MediaPolicyConfig(
    /**
     * Allows server-provided `file://` media URLs to be forwarded to clients.
     *
     * Default is false: remote or multiplayer servers should not direct clients to local files.
     * Advanced same-machine setups (e.g. a local-only music source plugin, including local cover
     * art) may opt in.
     */
    @SerialName("allow_local_files")
    val allowLocalFiles: Boolean = false,

    /**
     * Shared firewall policy for server-forwarded HTTP(S) media URLs.
     *
     * Both server and client apply this on a best-effort basis so obviously disallowed hosts or
     * private-network targets are rejected before playback / cover fetching proceeds.
     */
    @SerialName("firewall")
    val firewall: MediaFirewallConfig = MediaFirewallConfig(),

    /**
     * Maximum duration accepted for user-submitted tracks, in seconds.
     *
     * Unknown-duration tracks are rejected by the same policy unless the submitter has the
     * duration-policy bypass permission.
     */
    @SerialName("max_player_track_duration_seconds")
    val maxPlayerTrackDurationSeconds: Int = 3_600,

    /**
     * Hard server-side clamp for search page size, regardless of what a client requests.
     */
    @SerialName("max_search_results_per_page")
    val maxSearchResultsPerPage: Int = 50,

    /**
     * Lightweight per-player request rate limits applied before expensive upstream I/O.
     */
    @SerialName("rate_limit")
    val rateLimit: RequestRateLimitConfig = RequestRateLimitConfig(),
) {
    fun normalized(): MediaPolicyConfig = copy(
        firewall = firewall.normalized(),
        maxPlayerTrackDurationSeconds = maxPlayerTrackDurationSeconds.coerceIn(1, 604_800),
        maxSearchResultsPerPage = maxSearchResultsPerPage.coerceIn(1, 200),
        rateLimit = rateLimit.normalized(),
    )
}

@Serializable
data class CoverArtConfig(
    /**
     * Maximum compressed cover download size, in MiB, accepted before decode starts.
     */
    @SerialName("max_download_mebibytes")
    val maxDownloadMebibytes: Int = 64,

    /**
     * Hard reject cap for the reported source width or height before any image decode happens.
     */
    @SerialName("max_source_dimension")
    val maxSourceDimension: Int = 16_384,

    /**
     * Hard reject cap for the reported source pixel count before any image decode happens.
     */
    @SerialName("max_source_pixels")
    val maxSourcePixels: Long = 134_217_728L,

    /**
     * Upper bound for decode-time downscaling via ImageIO source subsampling.
     *
     * The actual subsampling factor is auto-calculated from the source dimensions and a derived
     * decode target based on [maxTextureSize], then clamped by this ceiling.
     */
    @SerialName("max_decode_downscale_factor")
    val maxDecodeDownscaleFactor: Int = 16,

    /**
     * Final square texture size uploaded to Minecraft after crop/resize.
     *
     * The decode path intentionally keeps a little headroom and targets roughly twice this size
     * before the final resize so texture quality stays decent without fully materializing very
     * large originals.
     */
    @SerialName("max_texture_size")
    val maxTextureSize: Int = 512,
) {
    fun normalized(): CoverArtConfig = copy(
        maxDownloadMebibytes = maxDownloadMebibytes.coerceIn(1, 256),
        maxSourceDimension = maxSourceDimension.coerceIn(64, 65_536),
        maxSourcePixels = maxSourcePixels.coerceIn(4_096L, 268_435_456L),
        maxDecodeDownscaleFactor = maxDecodeDownscaleFactor.coerceIn(1, 64),
        maxTextureSize = maxTextureSize.coerceIn(16, 2_048),
    )
}

@Serializable
data class RequestRateLimitConfig(
    val enabled: Boolean = true,

    /**
     * Sliding-window size in seconds for both search and submit buckets.
     */
    @SerialName("window_seconds")
    val windowSeconds: Int = 10,

    /**
     * Maximum search requests per player per window. `0` disables the search bucket.
     */
    @SerialName("search_requests")
    val searchRequests: Int = 8,

    /**
     * Maximum submit-like requests per player per window. `0` disables the submit bucket.
     */
    @SerialName("submit_requests")
    val submitRequests: Int = 6,
) {
    fun normalized(): RequestRateLimitConfig = copy(
        windowSeconds = windowSeconds.coerceIn(1, 3_600),
        searchRequests = searchRequests.coerceIn(0, 1_000),
        submitRequests = submitRequests.coerceIn(0, 1_000),
    )
}

/**
 * Client-local settings stored in the shared `moemusic.toml`.
 *
 * The dedicated server ignores these values; they exist so the client can persist
 * per-player preferences without maintaining a second config file.
 */
enum class HudAnchor {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

enum class HudCoverSide {
    LEFT,
    RIGHT,
}

enum class HudTextAlignment {
    LEFT,
    RIGHT,
}

enum class HudProgressBarPosition {
    TOP,
    BOTTOM,
}

enum class ContentFilterClientListMode {
    MARK,
    HIDE,
}

@Serializable
data class MediaFirewallConfig(
    val enabled: Boolean = true,
    @SerialName("host_list_mode")
    val hostListMode: MediaHostListMode = MediaHostListMode.BLACKLIST,
    @SerialName("hosts")
    val hosts: List<String> = emptyList(),
    @SerialName("block_private_ips")
    val blockPrivateIps: Boolean = true,
) {
    fun normalized(): MediaFirewallConfig = copy(
        hosts = hosts
            .asSequence()
            .map { it.trim().lowercase().trimStart('.') }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList(),
    )
}

@Serializable
data class ClientContentFilterConfig(
    val enabled: Boolean = true,
    @SerialName("search_list_mode")
    val searchListMode: ContentFilterClientListMode = ContentFilterClientListMode.HIDE,
    @SerialName("queue_list_mode")
    val queueListMode: ContentFilterClientListMode = ContentFilterClientListMode.MARK,
)

@Serializable
data class NowPlayingHudConfig(
    val enabled: Boolean = true,
    val anchor: HudAnchor = HudAnchor.TOP_LEFT,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val verticalSize: Int = 64,
    val textMaxWidth: Int = 180,
    val coverSide: HudCoverSide = HudCoverSide.LEFT,
    val textAlignment: HudTextAlignment = HudTextAlignment.LEFT,
    val progressBarPosition: HudProgressBarPosition = HudProgressBarPosition.TOP,
    val showBackground: Boolean = true,
    val showCover: Boolean = true,
    val showTitle: Boolean = true,
    val showArtist: Boolean = true,
    val showAlbum: Boolean = true,
    val showTime: Boolean = true,
    val showProgressBar: Boolean = true,
    val showLyrics: Boolean = true,
    val circularCover: Boolean = true,
    val showCenterDot: Boolean = false,
    val spinCover: Boolean = true,
    val textColorArgb: String = "FFFFFFFF",
    val secondaryTextColorArgb: String = "FFCCCCCC",
    val backgroundColorArgb: String = "4F000000",
    val progressBarColorArgb: String = "FF1DB954",
    val pausedProgressBarColorArgb: String = "FFF4D35E",
    val progressBarBackgroundColorArgb: String = "FF444444",
    val recordRingColorArgb: String = "FF000000",
) {
    fun normalized(): NowPlayingHudConfig = copy(
        offsetX = offsetX.coerceIn(0, 10_000),
        offsetY = offsetY.coerceIn(0, 10_000),
        verticalSize = verticalSize.coerceIn(16, 256),
        textMaxWidth = textMaxWidth.coerceIn(40, 1_024),
        textColorArgb = normalizeArgb(textColorArgb, "FFFFFFFF"),
        secondaryTextColorArgb = normalizeArgb(secondaryTextColorArgb, "FFCCCCCC"),
        backgroundColorArgb = normalizeArgb(backgroundColorArgb, "CC000000"),
        progressBarColorArgb = normalizeArgb(progressBarColorArgb, "FF1DB954"),
        pausedProgressBarColorArgb = normalizeArgb(pausedProgressBarColorArgb, "FFF4D35E"),
        progressBarBackgroundColorArgb = normalizeArgb(progressBarBackgroundColorArgb, "FF444444"),
        recordRingColorArgb = normalizeArgb(recordRingColorArgb, "FF000000"),
    )

    private fun normalizeArgb(value: String, fallback: String): String {
        val normalized = value.trim().removePrefix("#").uppercase()
        return if (normalized.matches(Regex("[0-9A-F]{8}"))) normalized else fallback
    }
}

@Serializable
data class ClientConfig(
    @SerialName("playback_enabled")
    val playbackEnabled: Boolean = true,
    /** Stop and suppress vanilla background music while MoeMusic is actively playing. */
    @SerialName("block_vanilla_music")
    val blockVanillaMusic: Boolean = true,
    /** Stop and suppress jukebox / record playback while MoeMusic is actively playing. */
    @SerialName("block_records")
    val blockRecords: Boolean = false,
    @SerialName("global_instance_playback_lock")
    val globalInstancePlaybackLock: Boolean = true,
    @SerialName("disabled_servers")
    val disabledServers: List<String> = emptyList(),
    @SerialName("content_filter")
    val contentFilter: ClientContentFilterConfig = ClientContentFilterConfig(),
    @SerialName("loudness_normalization")
    val loudnessNormalization: LoudnessNormalizationConfig = LoudnessNormalizationConfig(),
    @SerialName("cover_art")
    val coverArt: CoverArtConfig = CoverArtConfig(),
    /** Local playback volume percentage in the `0..100` range. */
    val volume: Int = ClientVolume.DEFAULT_PERCENT,
    @SerialName("join_shortcut_tip_shown")
    val joinShortcutTipShown: Boolean = false,
    val nowPlayingHud: NowPlayingHudConfig = NowPlayingHudConfig(),
) {
    fun normalized(): ClientConfig = copy(
        volume = ClientVolume.normalizePercent(volume),
        disabledServers = disabledServers
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList(),
        contentFilter = contentFilter,
        loudnessNormalization = loudnessNormalization.normalized(),
        coverArt = coverArt.normalized(),
        nowPlayingHud = nowPlayingHud.normalized(),
    )
}

@Serializable
data class LoudnessNormalizationConfig(
    val mode: LoudnessNormalizationMode = LoudnessNormalizationMode.ATTENUATE_ONLY,
    @SerialName("target_lufs")
    val targetLufs: Double = DEFAULT_TARGET_LUFS,
) {
    fun normalized(): LoudnessNormalizationConfig = copy(
        targetLufs = normalizedTargetLufs(),
    )

    fun gainForTrack(loudness: LoudnessInfo?): Float {
        if (mode == LoudnessNormalizationMode.OFF) return 1.0f
        val normalizedLoudness = loudness?.normalizedOrNull() ?: return 1.0f
        val trackLufs = normalizedLoudness.integratedLufs ?: return 1.0f
        val desiredDb = normalizedTargetLufs() - trackLufs
        if (desiredDb <= 0.0) {
            return 10.0.pow(desiredDb / 20.0).toFloat()
        }
        if (mode != LoudnessNormalizationMode.CONSERVATIVE_BOOST) return 1.0f

        val peak = normalizedLoudness.peak ?: return 1.0f
        val peakCeilingDbFs = when (peak.kind) {
            PeakKind.TRUE -> TRUE_PEAK_CEILING_DBFS
            PeakKind.SAMPLE, PeakKind.UNKNOWN -> SAMPLE_PEAK_CEILING_DBFS
        }
        val peakDbFs = 20.0 * log10(peak.amplitudeLinear)
        val peakSafeBoostDb = peakCeilingDbFs - peakDbFs
        val appliedDb = minOf(desiredDb, peakSafeBoostDb, USER_RELATIVE_MAX_BOOST_DB).coerceAtLeast(0.0)
        return 10.0.pow(appliedDb / 20.0).toFloat()
    }

    private fun normalizedTargetLufs(): Double =
        targetLufs.takeIf(Double::isFinite)?.coerceIn(MIN_TARGET_LUFS, MAX_TARGET_LUFS) ?: DEFAULT_TARGET_LUFS

    companion object {
        const val DEFAULT_TARGET_LUFS: Double = -14.0
        const val MIN_TARGET_LUFS: Double = -30.0
        const val MAX_TARGET_LUFS: Double = 0.0
        const val TRUE_PEAK_CEILING_DBFS: Double = -1.0
        const val SAMPLE_PEAK_CEILING_DBFS: Double = -2.0
        const val USER_RELATIVE_MAX_BOOST_DB: Double = 6.0  // 9.0 or 12.0?
    }
}

@Serializable
enum class LoudnessNormalizationMode {
    OFF,
    ATTENUATE_ONLY,
    CONSERVATIVE_BOOST,
}

/**
 * Configuration for autoplay.
 *
 * When [enabled] is true, MoeMusic fetches tracks from all registered [MusicSource]s via
 * `getAutoplayTracks()`, shuffles them into a non-repeating deck, and plays through it whenever
 * no player has manually queued a track. The deck is fully refreshed once it is exhausted.
 */
@Serializable
data class AutoplayConfig(
    /**
     * Master switch for autoplay.
     * When false, autoplay never plays and no fetching is performed.
     */
    val enabled: Boolean = true,

    /**
     * Maximum number of tracks accepted from a single [MusicSource] per refresh cycle.
     * Caps how many autoplay tracks one source can contribute, preventing a single
     * large source from dominating the deck.
     */
    @SerialName("max_tracks_per_source")
    val maxTracksPerSource: Int = 1000,
)
