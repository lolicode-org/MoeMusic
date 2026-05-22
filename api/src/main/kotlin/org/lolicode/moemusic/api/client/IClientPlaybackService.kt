package org.lolicode.moemusic.api.client

import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.model.TrackContext

/** Local client playback state and controls exposed to plugins. */
public interface IClientPlaybackService {

    /** Current local playback snapshot. Null when nothing is loaded locally. */
    public val currentContext: TrackContext?

    /** Server-provided search source catalog for the current connection, if available. */
    public val searchCatalog: ClientSearchCatalog?

    /** Current client participation state for this connection, or null before the handshake. */
    public val currentParticipationState: UserParticipationState?

    /** Current local availability issue for the builtin MoeMusic client UI, if any. */
    public val currentAvailabilityIssue: ClientAvailabilityIssue?

    /** Current configured/base playback volume in the normalized `0..100` range. */
    public val configuredVolumePercent: Int

    /**
     * Current effective playback volume in the normalized `0..100` range after transient runtime
     * overrides are applied.
     */
    public val effectiveVolumePercent: Int

    /** Compute the current local playback position for [currentContext], or null when stopped. */
    public fun currentPositionMs(): Long?

    /**
     * Set the configured/base playback volume in the normalized `0..100` range and persist it to
     * MoeMusic's client config.
     */
    public fun setConfiguredVolumePercent(percent: Int)

    /**
     * Apply a transient runtime-only volume [override] for [ownerId].
     *
     * Transient overrides affect only the effective playback volume. They must not be written into
     * MoeMusic's client config and are intended for temporary client-side integrations such as
     * ducking or focus-based attenuation.
     *
     * Current resolution is attenuation-oriented: the effective output is the lowest resolved
     * percent across all active overrides and the configured/base volume, so overrides never boost
     * playback above the configured MoeMusic volume.
     */
    public fun setTransientVolumeOverride(ownerId: String, override: ClientVolumeOverride)

    /**
     * Clear the transient runtime-only volume override previously registered for [ownerId].
     */
    public fun clearTransientVolumeOverride(ownerId: String)

    /** Returns `true` when MoeMusic playback is enabled for the current server scope. */
    public fun isPlaybackEnabledForCurrentServer(): Boolean

    /**
     * Enable or disable MoeMusic playback for the current server scope, then reconcile
     * participation with the live connection if needed.
     */
    public fun setPlaybackEnabledForCurrentServer(enabled: Boolean)

    /** Re-evaluate client participation after config or local state changes. */
    public fun syncParticipationWithCurrentConfig()
}
