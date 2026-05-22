package org.lolicode.moemusic.api.model

import kotlinx.serialization.Serializable

/**
 * User-visible artist identity exposed by a source.
 *
 * @property id   Source-local stable artist identifier when available. If blank, callers should
 *                fall back to [name] for best-effort exact matching and quick moderation actions.
 * @property name Current display name shown to users.
 */
@Serializable
public data class ArtistInfo(
    val id: String,
    val name: String,
) {
    /** Stable identity used for exact artist rules. Falls back to [name] when no id is available. */
    public val effectiveId: String
        get() = id.trim().ifEmpty { name.trim() }

    /** Best-effort label for UI display. Falls back to [effectiveId] when no name is available. */
    public val displayName: String
        get() = name.trim().ifEmpty { effectiveId }

    public companion object {
        /** Convenience factory for sources that only know the current display name. */
        public fun fromName(name: String): ArtistInfo = ArtistInfo(id = name, name = name)
    }
}

/** Convert plain artist display names into [ArtistInfo] values that fall back to name-as-id. */
public fun Iterable<String>.toArtistInfos(): List<ArtistInfo> = map(ArtistInfo::fromName)
