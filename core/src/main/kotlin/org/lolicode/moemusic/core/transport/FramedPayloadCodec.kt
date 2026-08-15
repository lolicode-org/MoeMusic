package org.lolicode.moemusic.core.transport

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Transport-level framing and compression codec for MoeMusic network payloads.
 *
 * Prepends a 1-byte discriminator to all outgoing framed payloads:
 * - [FLAG_RAW] (0x00): uncompressed, single-packet payload.
 * - [FLAG_COMPRESSED] (0x01): Deflate-compressed, single-packet payload.
 * - [FLAG_CHUNK_RAW] (0x02): uncompressed, multi-part chunk payload.
 * - [FLAG_CHUNK_COMPRESSED] (0x03): Deflate-compressed, multi-part chunk payload.
 */
object FramedPayloadCodec {

    const val FLAG_RAW: Byte = 0x00
    const val FLAG_COMPRESSED: Byte = 0x01
    const val FLAG_CHUNK_RAW: Byte = 0x02
    const val FLAG_CHUNK_COMPRESSED: Byte = 0x03

    /** Maximum payload size per chunk (30 KB), leaving safe margin below Spigot's 32,766-byte limit. */
    const val CHUNK_PAYLOAD_SIZE = 30 * 1024

    /** Maximum number of chunks allowed per transfer (max 35 * 30 KB >= 1 MB). */
    const val MAX_CHUNKS = 35

    /** Absolute maximum reassembled payload size (1 MB). */
    const val MAX_ASSEMBLED_BYTES = 1024 * 1024

    /** Maximum compressed payload bytes that can be represented across chunk frames (35 * 30 KB). */
    const val MAX_COMPRESSED_CHUNK_BYTES = MAX_CHUNKS * CHUNK_PAYLOAD_SIZE

    /** Payload byte threshold below which compression is skipped. */
    const val COMPRESSION_THRESHOLD_BYTES = 128

    /** Header size for chunked frames: Flag (1B) + TransferId (2B) + ChunkIndex (2B) + TotalChunks (2B) + TotalBytes (4B) = 11B. */
    const val CHUNK_HEADER_SIZE = 1 + 2 + 2 + 2 + 4

    /**
     * Next rotating transfer identifier, masked to 15 bits (0..32767) and wrapped on overflow.
     *
     * Wraparound can in principle reuse an ID that is still in flight on the client, but collisions are graceful:
     * the assembler drops any frame whose (totalChunks, totalBytes, isCompressed) does not match the existing
     * transfer for that ID, and the 15 s TTL bounds the in-flight window. Reaching wraparound inside the TTL
     * would require >32768 transfers within 15 s (~2.2k/s), which is unrealistic for this transport.
     */
    private val transferIdCounter = AtomicInteger(0)

    fun nextTransferId(): Short =
        (transferIdCounter.getAndIncrement() and 0x7FFF).toShort()

    /** Returns true when [payload] starts with a recognized transport framing flag. */
    fun isFramed(payload: ByteArray): Boolean =
        payload.firstOrNull()?.let(::isFramingFlag) == true

    /** Returns true when [flag] is one of the four transport framing flags. */
    private fun isFramingFlag(flag: Byte): Boolean =
        flag == FLAG_RAW || flag == FLAG_COMPRESSED || flag == FLAG_CHUNK_RAW || flag == FLAG_CHUNK_COMPRESSED

    fun isChunk(flag: Byte): Boolean =
        flag == FLAG_CHUNK_RAW || flag == FLAG_CHUNK_COMPRESSED

    /**
     * Encodes a raw Protobuf byte array into one or more transport frames.
     *
     * @param payload Raw Protobuf bytes to encode.
     * @param transferId Optional transfer identifier for multi-part frames (defaults to rotating short).
     * @return List of 1 or more frames, each <= [CHUNK_PAYLOAD_SIZE] + [CHUNK_HEADER_SIZE].
     */
    fun encode(
        payload: ByteArray,
        transferId: Short = nextTransferId(),
    ): List<ByteArray> {
        require(payload.size <= MAX_ASSEMBLED_BYTES) {
            "Payload size ${payload.size} exceeds maximum assembled bytes $MAX_ASSEMBLED_BYTES"
        }

        val compressed = if (payload.size >= COMPRESSION_THRESHOLD_BYTES) {
            compress(payload)
        } else null

        val useCompression = compressed != null && compressed.size < payload.size
        val dataToSend = if (useCompression) requireNotNull(compressed) else payload

        if (dataToSend.size <= CHUNK_PAYLOAD_SIZE) {
            val singleFlag = if (useCompression) FLAG_COMPRESSED else FLAG_RAW
            val single = ByteArray(1 + dataToSend.size)
            single[0] = singleFlag
            System.arraycopy(dataToSend, 0, single, 1, dataToSend.size)
            return listOf(single)
        }

        // Payload exceeds chunk size: split into chunk frames (compressed if beneficial, raw otherwise)
        val dataToChunk = dataToSend
        val chunkFlag = if (useCompression) FLAG_CHUNK_COMPRESSED else FLAG_CHUNK_RAW
        val totalBytes = dataToChunk.size
        require(totalBytes <= MAX_COMPRESSED_CHUNK_BYTES) {
            "Payload size $totalBytes exceeds maximum chunk capacity $MAX_COMPRESSED_CHUNK_BYTES"
        }

        val totalChunks = ((totalBytes + CHUNK_PAYLOAD_SIZE - 1) / CHUNK_PAYLOAD_SIZE).toShort()
        require(totalChunks.toInt() in 1..MAX_CHUNKS) {
            "Total chunks $totalChunks exceeds maximum chunk count $MAX_CHUNKS"
        }

        val chunks = ArrayList<ByteArray>(totalChunks.toInt())
        for (i in 0 until totalChunks.toInt()) {
            val offset = i * CHUNK_PAYLOAD_SIZE
            val len = minOf(CHUNK_PAYLOAD_SIZE, totalBytes - offset)
            val chunkBuf = ByteBuffer.allocate(CHUNK_HEADER_SIZE + len)
            chunkBuf.put(chunkFlag)
            chunkBuf.putShort(transferId)
            chunkBuf.putShort(i.toShort())
            chunkBuf.putShort(totalChunks)
            chunkBuf.putInt(totalBytes)
            chunkBuf.put(dataToChunk, offset, len)
            chunks.add(chunkBuf.array())
        }
        return chunks
    }

    /**
     * Decodes a single unfragmented frame ([FLAG_RAW] or [FLAG_COMPRESSED]).
     *
     * @throws IllegalArgumentException if the frame is empty, malformed, or is a chunked frame ([FLAG_CHUNK_RAW] or [FLAG_CHUNK_COMPRESSED]).
     */
    fun decodeSingle(frame: ByteArray): ByteArray {
        require(frame.isNotEmpty()) { "Cannot decode empty framed payload" }
        return when (val flag = frame[0]) {
            FLAG_RAW -> {
                val result = ByteArray(frame.size - 1)
                System.arraycopy(frame, 1, result, 0, result.size)
                result
            }

            FLAG_COMPRESSED -> {
                decompress(frame, 1, frame.size - 1)
            }

            FLAG_CHUNK_RAW, FLAG_CHUNK_COMPRESSED -> {
                throw IllegalArgumentException("Unexpected chunked frame in single-frame decoder")
            }

            else -> {
                throw IllegalArgumentException("Unknown framing flag: $flag")
            }
        }
    }

    /**
     * Unwraps an inbound C2S payload on the server.
     *
     * - If [FLAG_RAW] or [FLAG_COMPRESSED]: decodes and returns raw Protobuf bytes.
     * - If [FLAG_CHUNK_RAW] or [FLAG_CHUNK_COMPRESSED]: throws [IllegalArgumentException] (chunked C2S frames are forbidden).
     * - Otherwise: treated as legacy un-framed Wire Protobuf bytes.
     */
    fun unwrapServerInbound(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return raw
        return when (raw[0]) {
            FLAG_RAW, FLAG_COMPRESSED -> decodeSingle(raw)
            FLAG_CHUNK_RAW, FLAG_CHUNK_COMPRESSED -> throw IllegalArgumentException("Unexpected chunked frame in C2S packet")
            else -> raw
        }
    }

    /**
     * Compress [data] using Deflate ([Deflater.BEST_SPEED]).
     */
    fun compress(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(data, offset, length)
            deflater.finish()
            val output = ByteArrayOutputStream(minOf(length, 4096))
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Decompress Deflate-compressed [data].
     */
    fun decompress(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        expectedSize: Int = -1,
        maxDecompressedBytes: Int = MAX_ASSEMBLED_BYTES,
    ): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data, offset, length)
            val initialCapacity = if (expectedSize > 0) expectedSize else minOf(length * 2, 4096)
            val output = ByteArrayOutputStream(initialCapacity)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                if (inflater.needsDictionary()) {
                    throw IllegalArgumentException("Zlib preset dictionary is not supported")
                }
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsDictionary()) {
                        throw IllegalArgumentException("Zlib preset dictionary is not supported")
                    }
                    if (inflater.needsInput()) {
                        break
                    }
                } else {
                    if (output.size() + count > maxDecompressedBytes) {
                        throw IllegalArgumentException(
                            "Decompressed payload exceeds maximum size limit of $maxDecompressedBytes bytes"
                        )
                    }
                    output.write(buffer, 0, count)
                }
            }
            if (!inflater.finished()) {
                throw IllegalArgumentException("Incomplete or truncated deflate stream")
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
