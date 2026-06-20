package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.LocalizedText

/**
 * Result of a content-filter evaluation on a single track or selection entry.
 *
 * The full rejection reason is only surfaced to users who hold the
 * `moemusic.moderation.filter_manage` permission; other users receive a generic
 * "managed by server policy" message at the wire boundary.
 */
public sealed interface FilterVerdict {

    /** No rule matched; the track may proceed. */
    public data object Allow : FilterVerdict

    /**
     * A filter rule matched.
     *
     * Read-only sealed subtype. This type grows by adding new subtypes, not new fields.
     * Do not construct, destructure, or copy individual subtypes.
     *
     * @property reason User-visible rejection reason. The full detail (e.g. matched regex
     *   pattern) is only forwarded to users with `moemusic.moderation.filter_manage`
     *   permission; other users see a generic unavailability message.
     */
    public data class Reject(val reason: LocalizedText) : FilterVerdict
}
