package org.lolicode.moemusic.api.model

/**
 * User-visible artist identity exposed by a source.
 *
 * Construct one with the [ArtistInfo] factory / builder DSL (or [fromName] / [toArtistInfos]) — do
 * **not** implement this interface; it is sealed with a single internal implementation.
 *
 * @property id   Source-local stable artist identifier when available. If blank, callers should
 *                fall back to [name] for best-effort exact matching and quick moderation actions.
 * @property name Current display name shown to users.
 */
public sealed interface ArtistInfo {
    public val id: String
    public val name: String

    /** Stable identity used for exact artist rules. Falls back to [name] when no id is available. */
    public val effectiveId: String
        get() = id.trim().ifEmpty { name.trim() }

    /** Best-effort label for UI display. Falls back to [effectiveId] when no name is available. */
    public val displayName: String
        get() = name.trim().ifEmpty { effectiveId }

    /** Returns a builder seeded with this artist's values. */
    public fun toBuilder(): ArtistInfoBuilder

    public companion object {
        /** Convenience factory for sources that only know the current display name. */
        public fun fromName(name: String): ArtistInfo = ArtistInfo(id = name, name = name)
    }
}

/** Mutable builder for [ArtistInfo]. */
public class ArtistInfoBuilder internal constructor(
    public var id: String,
    public var name: String,
) {
    public fun build(): ArtistInfo = ArtistInfoImpl(id, name)
}

/**
 * Build an [ArtistInfo] from its identity fields plus an optional [configure] block.
 *
 * The signature is frozen; future optional fields are set inside [configure] via [ArtistInfoBuilder].
 */
public fun ArtistInfo(
    id: String,
    name: String,
    configure: ArtistInfoBuilder.() -> Unit = {},
): ArtistInfo = ArtistInfoBuilder(id, name).apply(configure).build()

/** Returns a copy of this artist with [configure] applied to a seeded builder. */
public fun ArtistInfo.copy(configure: ArtistInfoBuilder.() -> Unit): ArtistInfo =
    toBuilder().apply(configure).build()

internal data class ArtistInfoImpl(
    override val id: String,
    override val name: String,
) : ArtistInfo {
    override fun toBuilder(): ArtistInfoBuilder = ArtistInfoBuilder(id, name)
}

/** Convert plain artist display names into [ArtistInfo] values that fall back to name-as-id. */
public fun Iterable<String>.toArtistInfos(): List<ArtistInfo> = map(ArtistInfo::fromName)
