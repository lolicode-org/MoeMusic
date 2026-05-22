package org.lolicode.moemusic.core.protocol

import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.protocol.proto.SelectionEntryProto
import org.lolicode.moemusic.core.protocol.proto.TrackInfoProto
import org.lolicode.moemusic.core.playback.toProto

object ProtocolViewMapper {

    fun trackToClientProto(
        track: TrackInfo,
        canBypass: Boolean,
        canSeeDetail: Boolean,
        render: (LocalizedText) -> String,
    ): TrackInfoProto {
        val filterReason: String = if (canBypass) {
            ""
        } else {
            when (val verdict = ContentFilterRuntime.trackFilterVerdict(track)) {
                FilterVerdict.Allow -> ""
                is FilterVerdict.Reject -> render(
                    if (canSeeDetail) verdict.reason
                    else LocalizedText.key("error.moemusic.content_filter.managed"),
                )
            }
        }
        val inherentReason = track.unavailableReason?.let(render).orEmpty()
        return track.toProto().copy(
            unavailable_reason = filterReason.ifEmpty { inherentReason },
        )
    }

    fun selectionToClientProto(
        entry: SelectionEntry,
        canBypass: Boolean,
        canSeeDetail: Boolean,
        render: (LocalizedText) -> String,
    ): SelectionEntryProto {
        val filterReason: String = if (canBypass) {
            ""
        } else {
            when (val verdict = ContentFilterRuntime.selectionFilterVerdict(entry)) {
                FilterVerdict.Allow -> ""
                is FilterVerdict.Reject -> render(
                    if (canSeeDetail) verdict.reason
                    else LocalizedText.key("error.moemusic.content_filter.managed"),
                )
            }
        }
        val inherentReason = entry.unavailableReason?.let(render).orEmpty()
        return entry.toProto().copy(
            unavailable_reason = filterReason.ifEmpty { inherentReason },
        )
    }
}
