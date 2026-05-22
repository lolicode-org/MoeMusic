package org.lolicode.moemusic.api.plugin

/**
 * Session-scoped lease indicating that some external audience is currently available to hear
 * server-side playback.
 *
 * Plugins that proxy or bridge playback to non-native clients should acquire one lease while
 * they have at least one active listener, then [release] it when that audience disappears.
 *
 * Lease release is idempotent. All outstanding leases are also invalidated automatically when
 * the current Minecraft server session shuts down.
 */
public interface PlaybackAudienceLease {

    /** Release this lease. Safe to call more than once. */
    public fun release()
}
