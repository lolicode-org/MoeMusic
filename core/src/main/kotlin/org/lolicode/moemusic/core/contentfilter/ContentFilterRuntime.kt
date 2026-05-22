package org.lolicode.moemusic.core.contentfilter

import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.api.service.IContentFilterService
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.core.config.ClientContentFilterConfig
import org.lolicode.moemusic.core.config.ContentFilterClientListMode
import org.lolicode.moemusic.core.config.MoeMusicConfig
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * Shared content-filter runtime used by both authoritative server enforcement and local client fallback.
 *
 * The service keeps an atomically replaceable compiled snapshot so config saves and reloads can
 * cheaply swap in new rule state without rebuilding call sites or event subscriptions.
 */
object ContentFilterRuntime : IContentFilterService {

    private val logger = LoggerFactory.getLogger(ContentFilterRuntime::class.java)

    @Volatile
    private var snapshot: Snapshot = Snapshot()

    override val currentRules: ContentFilterRules
        get() = snapshot.rules

    fun applyConfig(config: MoeMusicConfig) {
        snapshot = Snapshot.fromConfig(config)
    }

    override fun isExactTrackBlocked(sourceId: String?, trackId: String): Boolean {
        if (trackId.isBlank()) return false
        val normalizedSource = normalizeContentFilterKey(sourceId)
        if (normalizedSource.isBlank()) return false
        return snapshot.exactTrackRules.contains(TrackRuleKey(normalizedSource, normalizeContentFilterKey(trackId)))
    }

    override fun isExactArtistBlocked(sourceId: String?, artistId: String): Boolean {
        if (artistId.isBlank()) return false
        val normalizedSource = normalizeContentFilterKey(sourceId)
        if (normalizedSource.isBlank()) return false
        return snapshot.exactArtistRules.contains(ArtistRuleKey(normalizedSource, normalizeContentFilterKey(artistId)))
    }

    override fun trackFilterVerdict(track: TrackInfo): FilterVerdict {
        track.sourceFilterVerdict?.let { if (it is FilterVerdict.Reject) return it }

        val reason = trackBlockReason(track)
        if (reason != null) return FilterVerdict.Reject(reason)
        return FilterVerdict.Allow
    }

    override fun selectionFilterVerdict(entry: SelectionEntry): FilterVerdict {
        entry.sourceFilterVerdict?.let { if (it is FilterVerdict.Reject) return it }

        val reason = selectionBlockReason(entry)
        if (reason != null) return FilterVerdict.Reject(reason)
        return FilterVerdict.Allow
    }

    override fun textFilterVerdict(scope: ContentFilterTextRuleScope, values: Iterable<String>): FilterVerdict {
        val reason = textBlockReason(scope, values) ?: return FilterVerdict.Allow
        return FilterVerdict.Reject(reason)
    }

    override fun miscFilterVerdict(values: Iterable<String>): FilterVerdict =
        textFilterVerdict(ContentFilterTextRuleScope.MISC, values)

    fun trackBlockReason(track: TrackInfo): LocalizedText? {
        val current = snapshot
        if (!current.rules.enabled) return null

        exactTrackReason(track.sourceId, track.id, track.title.ifBlank { track.id })?.let { return it }
        exactArtistReason(track.sourceId, track.artists)?.let { return it }
        return textRuleReason(
            title = track.title,
            artist = track.artistDisplay,
            album = track.album.orEmpty(),
            compiledRules = current.textRules,
        )
    }

    fun selectionBlockReason(entry: SelectionEntry): LocalizedText? {
        val current = snapshot
        if (!current.rules.enabled) return null

        exactTrackReason(
            sourceId = entry.sourceId,
            trackId = entry.directTrackId,
            displayValue = entry.title.ifBlank { entry.directTrackId.orEmpty() },
        )?.let { return it }
        exactArtistReason(entry.sourceId, entry.artists)?.let { return it }
        return textRuleReason(
            title = entry.title,
            artist = entry.artistDisplay,
            album = entry.album.orEmpty(),
            compiledRules = current.textRules,
        )
    }

    fun textBlockReason(scope: ContentFilterTextRuleScope, values: Iterable<String>): LocalizedText? {
        val current = snapshot
        if (!current.rules.enabled) return null
        return scopedTextRuleReason(
            scope = scope,
            values = values,
            compiledRules = current.textRules,
        )
    }

    fun clientFilterEnabled(): Boolean =
        snapshot.rules.enabled && snapshot.clientConfig.enabled

    fun searchListMode(): ContentFilterClientListMode = snapshot.clientConfig.searchListMode

    fun queueListMode(): ContentFilterClientListMode = snapshot.clientConfig.queueListMode

    private fun exactTrackReason(sourceId: String?, trackId: String?, displayValue: String): LocalizedText? {
        val normalizedTrackId = normalizeContentFilterKey(trackId)
        if (normalizedTrackId.isBlank()) return null
        if (!isExactTrackBlocked(sourceId, normalizedTrackId)) return null
        return LocalizedText.key(
            "error.moemusic.content_filter.track_blocked",
            displayValue.ifBlank { normalizedTrackId })
    }

    private fun exactArtistReason(sourceId: String?, artists: List<ArtistInfo>): LocalizedText? =
        artists.firstOrNull { artist ->
            artist.effectiveId.isNotBlank() && isExactArtistBlocked(sourceId, artist.effectiveId)
        }?.let { artist ->
            LocalizedText.key(
                "error.moemusic.content_filter.artist_blocked",
                artist.displayName.ifBlank { artist.effectiveId },
            )
        }

    private fun textRuleReason(
        title: String,
        artist: String,
        album: String,
        compiledRules: List<CompiledTextRule>,
    ): LocalizedText? {
        return compiledRules.firstOrNull { rule ->
            rule.matchesCommonFields(
                title = title,
                artist = artist,
                album = album,
            )
        }?.let { rule ->
            LocalizedText.key("error.moemusic.content_filter.text_blocked", rule.displayPattern)
        }
    }

    private fun scopedTextRuleReason(
        scope: ContentFilterTextRuleScope,
        values: Iterable<String>,
        compiledRules: List<CompiledTextRule>,
    ): LocalizedText? {
        val materialized = values.map(String::trim).filter(String::isNotEmpty)
        if (materialized.isEmpty()) return null
        return compiledRules.firstOrNull { rule -> rule.matchesScopedValues(scope, materialized) }
            ?.let { rule -> LocalizedText.key("error.moemusic.content_filter.text_blocked", rule.displayPattern) }
    }

    private data class Snapshot(
        val rules: ContentFilterRules = ContentFilterRules(),
        val clientConfig: ClientContentFilterConfig = ClientContentFilterConfig(),
        val exactTrackRules: Set<TrackRuleKey> = emptySet(),
        val exactArtistRules: Set<ArtistRuleKey> = emptySet(),
        val textRules: List<CompiledTextRule> = emptyList(),
    ) {
        companion object {
            fun fromConfig(config: MoeMusicConfig): Snapshot {
                val normalizedRules = config.contentFilter.normalized()
                val compiledTextRules = buildList {
                    normalizedRules.textRules.forEach { rule ->
                        CompiledTextRule.create(rule)?.let(::add)
                    }
                }
                return Snapshot(
                    rules = normalizedRules,
                    clientConfig = config.client.contentFilter,
                    exactTrackRules = normalizedRules.exactTrackRules.mapTo(linkedSetOf()) { rule ->
                        TrackRuleKey(normalizeContentFilterKey(rule.sourceId), normalizeContentFilterKey(rule.trackId))
                    }.filterNot { it.sourceId.isBlank() || it.trackId.isBlank() }.toSet(),
                    exactArtistRules = normalizedRules.exactArtistRules.mapTo(linkedSetOf()) { rule ->
                        ArtistRuleKey(
                            normalizeContentFilterKey(rule.sourceId),
                            normalizeContentFilterKey(rule.artistId)
                        )
                    }.filterNot { it.sourceId.isBlank() || it.artistId.isBlank() }.toSet(),
                    textRules = compiledTextRules,
                )
            }
        }
    }

    private data class TrackRuleKey(
        val sourceId: String,
        val trackId: String,
    )

    private data class ArtistRuleKey(
        val sourceId: String,
        val artistId: String,
    )

    private data class CompiledTextRule(
        val sourceRule: ContentFilterTextRule,
        val regex: Regex?,
        val displayPattern: String,
    ) {
        fun matchesCommonFields(
            title: String,
            artist: String,
            album: String,
        ): Boolean {
            return when (sourceRule.scope) {
                ContentFilterTextRuleScope.QUERY -> false
                ContentFilterTextRuleScope.TITLE -> matchesField(title)
                ContentFilterTextRuleScope.ARTIST -> matchesField(artist)
                ContentFilterTextRuleScope.ALBUM -> matchesField(album)
                ContentFilterTextRuleScope.MISC -> false
                ContentFilterTextRuleScope.ALL ->
                    matchesField(title) ||
                            matchesField(artist) ||
                            matchesField(album)
            }
        }

        fun matchesScopedValues(scope: ContentFilterTextRuleScope, values: Iterable<String>): Boolean {
            val ruleMatchesScope = when (scope) {
                ContentFilterTextRuleScope.ALL -> sourceRule.scope == ContentFilterTextRuleScope.ALL
                else -> sourceRule.scope == scope || sourceRule.scope == ContentFilterTextRuleScope.ALL
            }
            return ruleMatchesScope && values.any(::matchesField)
        }

        private fun matchesField(raw: String): Boolean {
            if (raw.isBlank()) return false
            return when (sourceRule.mode) {
                ContentFilterTextRuleMode.SUBSTRING -> raw.contains(displayPattern, ignoreCase = sourceRule.ignoreCase)
                ContentFilterTextRuleMode.REGEX -> regex?.containsMatchIn(raw) == true
            }
        }

        companion object {
            fun create(rule: ContentFilterTextRule): CompiledTextRule? {
                val trimmedPattern = rule.pattern.trim()
                if (trimmedPattern.isBlank()) return null
                val regex = when (rule.mode) {
                    ContentFilterTextRuleMode.SUBSTRING -> null
                    ContentFilterTextRuleMode.REGEX -> {
                        val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        runCatching { Regex(trimmedPattern, options) }
                            .onFailure {
                                logger.warn(
                                    "Ignoring invalid content-filter regex '{}': {}",
                                    trimmedPattern,
                                    it.message,
                                )
                            }
                            .getOrNull()
                    }
                }
                if (rule.mode == ContentFilterTextRuleMode.REGEX && regex == null) return null
                return CompiledTextRule(
                    sourceRule = rule,
                    regex = regex,
                    displayPattern = trimmedPattern,
                )
            }
        }
    }
}

fun ContentFilterRules.normalized(): ContentFilterRules = copy(
    exactTrackRules = exactTrackRules.normalizedExactRules(
        normalize = { rule ->
            rule.copy(
                sourceId = rule.sourceId.trim(),
                trackId = rule.trackId.trim(),
                note = rule.note?.trim()?.takeIf(String::isNotEmpty),
            )
        },
        sourceId = ExactTrackFilterRule::sourceId,
        itemId = ExactTrackFilterRule::trackId,
        comparator = compareBy(ExactTrackFilterRule::sourceId, ExactTrackFilterRule::trackId),
    ),
    exactArtistRules = exactArtistRules.normalizedExactRules(
        normalize = { rule ->
            rule.copy(
                sourceId = rule.sourceId.trim(),
                artistId = rule.artistId.trim(),
                note = rule.note?.trim()?.takeIf(String::isNotEmpty),
            )
        },
        sourceId = ExactArtistFilterRule::sourceId,
        itemId = ExactArtistFilterRule::artistId,
        comparator = compareBy(ExactArtistFilterRule::sourceId, ExactArtistFilterRule::artistId),
    ),
    textRules = textRules
        .map { rule -> rule.copy(pattern = rule.pattern.trim()) }
        .filter { rule -> rule.pattern.isNotBlank() }
        .distinct()
        .sortedWith(
            compareBy(
                ContentFilterTextRule::scope,
                ContentFilterTextRule::mode,
                ContentFilterTextRule::pattern
            )
        ),
)

internal fun normalizeContentFilterKey(value: String?): String =
    value.orEmpty().trim().lowercase(Locale.ROOT)

internal fun contentFilterExactRuleKey(sourceId: String?, itemId: String?): String =
    "${normalizeContentFilterKey(sourceId)}\u0000${normalizeContentFilterKey(itemId)}"

private fun <T> Iterable<T>.normalizedExactRules(
    normalize: (T) -> T,
    sourceId: (T) -> String,
    itemId: (T) -> String,
    comparator: Comparator<T>,
): List<T> {
    val deduped = LinkedHashMap<String, T>()
    for (rule in this) {
        val normalized = normalize(rule)
        if (sourceId(normalized).isNotBlank() && itemId(normalized).isNotBlank()) {
            deduped[contentFilterExactRuleKey(sourceId(normalized), itemId(normalized))] = normalized
        }
    }
    return deduped.values.sortedWith(comparator)
}
