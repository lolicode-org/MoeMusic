package org.lolicode.moemusic.core.playback

import org.lolicode.moemusic.core.protocol.proto.SyncRequest
import org.lolicode.moemusic.core.protocol.proto.SyncResponse

/**
 * Monotonic clock synchronisation helper.
 *
 * Uses [System.nanoTime] exclusively — never wall-clock time.
 *
 * **Server side:** call [handleSyncRequest] when a [SyncRequest] arrives.
 * **Client side:** call [computeClientOffset] when the [SyncResponse] arrives to obtain
 * the nanosecond offset that converts local monotonic time to the server's monotonic time.
 *
 * Formula:
 * ```
 * serverMid    = (serverRecv + serverSend) / 2
 * clientMid    = (clientSend + clientRecv) / 2
 * serverOffset = serverMid - clientMid          // add to client nanoTime → server nanoTime
 * ```
 */
class TimeSyncHandler {

    /**
     * Server-side: build a [SyncResponse] for the incoming [req].
     * Records timestamps as tightly around the processing boundary as possible.
     */
    fun handleSyncRequest(req: SyncRequest): SyncResponse {
        val serverRecv = System.nanoTime()
        val serverSend = System.nanoTime()
        return SyncResponse(
            client_send_monotonic = req.client_send_monotonic,
            server_recv_monotonic = serverRecv,
            server_send_monotonic = serverSend,
        )
    }

    /**
     * Client-side: compute the nanosecond offset from the client's clock to the server's clock.
     *
     * Call this immediately when the [SyncResponse] arrives so that [System.nanoTime] is as
     * close to the actual receive time as possible.
     *
     * @return `serverOffset` in nanoseconds. Add to `System.nanoTime()` to get estimated server time.
     */
    fun computeClientOffset(resp: SyncResponse): Long {
        val clientRecv = System.nanoTime()
        val serverMid = (resp.server_recv_monotonic + resp.server_send_monotonic) / 2L
        val clientMid = (resp.client_send_monotonic + clientRecv) / 2L
        return serverMid - clientMid
    }
}
