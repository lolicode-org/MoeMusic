package org.lolicode.moemusic.api.service

import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.model.ContentFilterRules
import org.lolicode.moemusic.api.model.ContentFilterTextRuleScope
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackInfo

/**
 * Shared read-only access to the active content-filter rules and common-field helpers.
 *
 * Plugins may inspect [currentRules] for source-specific filtering on richer metadata while still
 * relying on MoeMusic's built-in enforcement for the shared track / selection / playback flows.
 */
public interface IContentFilterService {

    /** Currently applied shared ruleset. */
    public val currentRules: ContentFilterRules

    /** Exact source-local track check. */
    public fun isExactTrackBlocked(sourceId: String?, trackId: String): Boolean

    /** Exact source-local artist check. */
    public fun isExactArtistBlocked(sourceId: String?, artistId: String): Boolean

    /**
     * Shared-rule verdict for a concrete [TrackInfo].
     *
     * Returns [FilterVerdict.Allow] when no rule matches, or [FilterVerdict.Reject] with the
     * rejection reason. The full reason detail (e.g. a regex pattern) is only intended for
     * users with `moemusic.moderation.filter_manage`; the wire layer masks it for others.
     */
    public fun trackFilterVerdict(track: TrackInfo): FilterVerdict

    /**
     * Shared-rule verdict for a user-visible [SelectionEntry].
     *
     * See [trackFilterVerdict] for semantics.
     */
    public fun selectionFilterVerdict(entry: SelectionEntry): FilterVerdict

    /**
     * Shared-rule check for caller-provided text values under a specific shared scope.
     *
     * Returns a [FilterVerdict] so callers can *observe* whether values would be blocked,
     * without committing to a particular action. **Sources should not translate a [FilterVerdict.Reject]
     * result into [TrackInfo.unavailableReason] or [UserResult.Error]** — doing so would make the
     * rejection un-bypassable for privileged users. Instead, sources should return the track
     * normally and let the submission gate and wire boundary enforce or mask the verdict.
     *
     * This method is primarily useful for sources that want to provide *display* metadata (e.g.
     * graying out an entry in the UI before the user submits it), or for sources that need to
     * short-circuit expensive network I/O when they can determine upfront that all results would
     * be filtered.
     *
     * Specific text scopes also include `ALL` rules, so a caller querying
     * [ContentFilterTextRuleScope.QUERY] or [ContentFilterTextRuleScope.MISC] will see both the
     * specific scope and `ALL` matches. Passing [ContentFilterTextRuleScope.ALL] checks only rules
     * explicitly scoped to `ALL`.
     */
    public fun textFilterVerdict(scope: ContentFilterTextRuleScope, values: Iterable<String>): FilterVerdict

    /**
     * Shortcut for `textFilterVerdict(MISC, values)`.
     *
     * See [textFilterVerdict] for the full contract.  Sources should not propagate a
     * [FilterVerdict.Reject] from this method into [TrackInfo.unavailableReason]; returning the
     * track normally and letting the submission gate enforce is the correct pattern.
     */
    public fun miscFilterVerdict(values: Iterable<String>): FilterVerdict
}
