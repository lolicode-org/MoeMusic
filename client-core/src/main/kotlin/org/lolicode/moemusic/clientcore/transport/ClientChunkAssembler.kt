package org.lolicode.moemusic.clientcore.transport

import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side buffer manager for reassembling multi-part chunk frames.
 *
 * Thread-safe. Automatically enforces:
 * - Max payload size ([FramedPayloadCodec.MAX_ASSEMBLED_BYTES])
 * - Max chunk count ([FramedPayloadCodec.MAX_CHUNKS])
 * - TTL expiration ([ttlNanos])
 * - Max concurrent transfers ([maxConcurrentTransfers])
 */
class ClientChunkAssembler(
    private val maxConcurrentTransfers: Int = 4,
    private val ttlNanos: Long = 15_000_000_000L, // 15 seconds
) {

    private val logger = LoggerFactory.getLogger(ClientChunkAssembler::class.java)

    private class InFlightTransfer(
        val totalChunks: Int,
        val totalBytes: Int,
        val isCompressed: Boolean,
        val createdNanos: Long,
    ) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        var receivedBytes = 0
        var receivedCount = 0
        var isFinished = false

        fun isComplete(): Boolean = receivedCount == totalChunks
    }

    private val transfers = ConcurrentHashMap<Short, InFlightTransfer>()

    /**
     * Process an inbound server-to-client frame.
     *
     * @param frame Raw frame received from the network.
     * @return Complete, decompressed Protobuf byte array if ready; `null` if more chunks are pending or frame is invalid.
     */
    fun process(frame: ByteArray): ByteArray? {
        if (frame.isEmpty()) return null

        val flag = frame[0]
        if (flag == FramedPayloadCodec.FLAG_RAW || flag == FramedPayloadCodec.FLAG_COMPRESSED) {
            return try {
                FramedPayloadCodec.decodeSingle(frame)
            } catch (e: Exception) {
                logger.error("Failed to decode single S2C framed payload: {}", e.message)
                null
            }
        }

        if (flag != FramedPayloadCodec.FLAG_CHUNK_RAW && flag != FramedPayloadCodec.FLAG_CHUNK_COMPRESSED) {
            // Legacy un-framed Wire Protobuf bytes
            if (frame.size > FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES) {
                logger.warn("Dropping oversized legacy S2C payload (size={})", frame.size)
                return null
            }
            return frame
        }

        if (frame.size < FramedPayloadCodec.CHUNK_HEADER_SIZE) {
            logger.warn("Dropping truncated S2C chunk frame (size={})", frame.size)
            return null
        }

        val transferId = readShort(frame, 1)
        val chunkIdx = readUnsignedShort(frame, 3)
        val totalChunks = readUnsignedShort(frame, 5)
        val totalBytes = readInt(frame, 7)
        val isCompressed = flag == FramedPayloadCodec.FLAG_CHUNK_COMPRESSED

        val maxAllowedBytes = if (isCompressed) {
            FramedPayloadCodec.MAX_COMPRESSED_CHUNK_BYTES
        } else {
            FramedPayloadCodec.MAX_ASSEMBLED_BYTES
        }

        if (totalBytes !in 1..maxAllowedBytes ||
            totalChunks !in 1..FramedPayloadCodec.MAX_CHUNKS ||
            chunkIdx !in 0 until totalChunks
        ) {
            logger.warn(
                "Dropping invalid S2C chunk frame: transferId={} chunkIdx={}/{} totalBytes={}",
                transferId,
                chunkIdx,
                totalChunks,
                totalBytes,
            )
            return null
        }

        val expectedOffset = chunkIdx * FramedPayloadCodec.CHUNK_PAYLOAD_SIZE
        val expectedChunkLen = minOf(FramedPayloadCodec.CHUNK_PAYLOAD_SIZE, totalBytes - expectedOffset)
        val chunkLen = frame.size - FramedPayloadCodec.CHUNK_HEADER_SIZE

        if (chunkLen != expectedChunkLen || chunkLen <= 0 || expectedOffset + chunkLen > totalBytes) {
            logger.warn(
                "Dropping malformed S2C chunk body (len={} expected={}) for transferId={}",
                chunkLen,
                expectedChunkLen,
                transferId,
            )
            return null
        }

        val now = System.nanoTime()
        val transfer = synchronized(transfers) {
            val existing = transfers[transferId]
            if (existing != null) {
                if (existing.totalChunks != totalChunks || existing.totalBytes != totalBytes || existing.isCompressed != isCompressed) {
                    logger.warn("Dropping mismatched S2C chunk frame for transferId={}", transferId)
                    return null
                }
                existing
            } else {
                evictExpired(now)
                if (transfers.size >= maxConcurrentTransfers) {
                    logger.warn("Dropping S2C chunk for transferId={} due to concurrent transfer limit", transferId)
                    return null
                }
                InFlightTransfer(totalChunks, totalBytes, isCompressed, now).also { transfers[transferId] = it }
            }
        }

        synchronized(transfer) {
            if (transfer.chunks[chunkIdx] == null) {
                if (transfer.receivedBytes + chunkLen > transfer.totalBytes) {
                    logger.warn("Dropping overflowing S2C chunk for transferId={}", transferId)
                    synchronized(transfers) { transfers.remove(transferId, transfer) }
                    return null
                }
                val chunkData = ByteArray(chunkLen)
                System.arraycopy(frame, FramedPayloadCodec.CHUNK_HEADER_SIZE, chunkData, 0, chunkLen)
                transfer.chunks[chunkIdx] = chunkData
                transfer.receivedBytes += chunkLen
                transfer.receivedCount++
            }

            if (transfer.isComplete()) {
                if (transfer.isFinished) return null
                transfer.isFinished = true
                synchronized(transfers) { transfers.remove(transferId, transfer) }
                if (transfer.receivedBytes != transfer.totalBytes) {
                    logger.error(
                        "Chunk assembly failed: received {} bytes, expected {} bytes",
                        transfer.receivedBytes,
                        transfer.totalBytes,
                    )
                    return null
                }

                val assembled = ByteArray(transfer.totalBytes)
                var offset = 0
                for (chunk in transfer.chunks) {
                    if (chunk == null) return null
                    System.arraycopy(chunk, 0, assembled, offset, chunk.size)
                    offset += chunk.size
                }

                return if (transfer.isCompressed) {
                    try {
                        FramedPayloadCodec.decompress(assembled, 0, assembled.size)
                    } catch (e: Exception) {
                        logger.error("Failed to decompress assembled chunk payload (transferId={}): {}", transferId, e.message)
                        null
                    }
                } else {
                    assembled
                }
            }
        }

        return null
    }

    /** Clear all in-flight chunk buffers (e.g. on disconnect). */
    fun clear() {
        synchronized(transfers) {
            transfers.clear()
        }
    }

    private fun readShort(frame: ByteArray, offset: Int): Short =
        (((frame[offset].toInt() and 0xFF) shl 8) or
            (frame[offset + 1].toInt() and 0xFF)).toShort()

    private fun readUnsignedShort(frame: ByteArray, offset: Int): Int =
        ((frame[offset].toInt() and 0xFF) shl 8) or
            (frame[offset + 1].toInt() and 0xFF)

    private fun readInt(frame: ByteArray, offset: Int): Int =
        ((frame[offset].toInt() and 0xFF) shl 24) or
            ((frame[offset + 1].toInt() and 0xFF) shl 16) or
            ((frame[offset + 2].toInt() and 0xFF) shl 8) or
            (frame[offset + 3].toInt() and 0xFF)

    /**
     * Evict transfers whose TTL has expired.
     *
     * Eviction is intentionally lazy and access-driven: it runs only when [process] is invoked, never on a
     * background timer. An orphaned partial transfer that never receives further chunks therefore keeps its
     * slot until the next [process] call or [clear] on disconnect. This is acceptable because memory is hard-bounded
     * by [maxConcurrentTransfers] (default 4) × [FramedPayloadCodec.MAX_COMPRESSED_CHUNK_BYTES] per transfer
     * (~1 MB), and [clear] is invoked on every connect/disconnect cycle, so no unbounded accumulation is possible.
     */
    private fun evictExpired(nowNanos: Long) {
        transfers.entries.removeIf { (_, transfer) ->
            (nowNanos - transfer.createdNanos) > ttlNanos
        }
    }
}
