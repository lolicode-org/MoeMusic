package org.lolicode.moemusic.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact source-local track block rule with an optional human note. */
@Serializable
public data class ExactTrackFilterRule(
    @SerialName("source_id")
    val sourceId: String,
    @SerialName("track_id")
    val trackId: String,
    val note: String? = null,
)

/** Exact source-local artist block rule with an optional human note. */
@Serializable
public data class ExactArtistFilterRule(
    @SerialName("source_id")
    val sourceId: String,
    @SerialName("artist_id")
    val artistId: String,
    val note: String? = null,
)

/** Matching strategy for common text rules. */
@Serializable
public enum class ContentFilterTextRuleMode {
    SUBSTRING,
    REGEX,
}

/** Administrative action applied to shared content-filter rules. */
@Serializable
public enum class ContentFilterRuleAction {
    BAN,
    UNBAN,
    TOGGLE,
}

/** User-visible text fields that a shared text rule may inspect. */
@Serializable
public enum class ContentFilterTextRuleScope {
    /** Search query text before it is sent to a music source. Server-side only. */
    QUERY,
    TITLE,
    ARTIST,
    ALBUM,
    /** Plugin / source-provided extra text such as descriptions, tags, or remarks. */
    MISC,
    /** Every non-exact text scope: query, title, artist, album, and misc values. */
    ALL,
}

/** Shared common-field text rule. */
@Serializable
public data class ContentFilterTextRule(
    val pattern: String,
    val mode: ContentFilterTextRuleMode = ContentFilterTextRuleMode.SUBSTRING,
    val scope: ContentFilterTextRuleScope = ContentFilterTextRuleScope.ALL,
    @SerialName("ignore_case")
    val ignoreCase: Boolean = true,
)

/**
 * Shared content-filter ruleset used by both server-side enforcement and local client fallback.
 */
@Serializable
public data class ContentFilterRules(
    val enabled: Boolean = false,
    @SerialName("exact_track_rules")
    val exactTrackRules: List<ExactTrackFilterRule> = emptyList(),
    @SerialName("exact_artist_rules")
    val exactArtistRules: List<ExactArtistFilterRule> = emptyList(),
    @SerialName("text_rules")
    val textRules: List<ContentFilterTextRule> = emptyList(),
)
