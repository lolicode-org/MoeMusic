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

    /** Maximum number of chunks allowed per transfer (max 70 * 30 KB = 2,150,400 B >= 2 MB). */
    const val MAX_CHUNKS = 70

    /** Absolute maximum reassembled/raw S2C payload size (2 MB). */
    const val MAX_RAW_PAYLOAD_BYTES = 2 * 1024 * 1024

    /** Legacy alias for [MAX_RAW_PAYLOAD_BYTES] (2 MB). */
    const val MAX_ASSEMBLED_BYTES = MAX_RAW_PAYLOAD_BYTES

    /** Maximum raw client-to-server payload (64 KB) to bound server memory and protect against decompression bombs. */
    const val MAX_RAW_C2S_PAYLOAD_BYTES = 64 * 1024

    /** Header size for chunked frames: Flag (1B) + TransferId (2B) + ChunkIndex (2B) + TotalChunks (2B) + TotalBytes (4B) = 11B. */
    const val CHUNK_HEADER_SIZE = 1 + 2 + 2 + 2 + 4

    /** Maximum unframed client-to-server payload supported by every platform transport. */
    const val MAX_LEGACY_C2S_PAYLOAD_BYTES = 32_766

    /** Maximum unframed server-to-client payload; preserves Vanilla Minecraft's 1 MiB CustomPayloadPacket limit. */
    const val MAX_LEGACY_S2C_PAYLOAD_BYTES = 1024 * 1024

    /** Maximum encoded size of a single framed payload. */
    const val MAX_SINGLE_FRAME_BYTES = 1 + CHUNK_PAYLOAD_SIZE

    /** Maximum encoded size of a chunk frame. */
    const val MAX_CHUNK_FRAME_BYTES = CHUNK_HEADER_SIZE + CHUNK_PAYLOAD_SIZE

    /** Maximum payload bytes that can be represented across chunk frames (70 * 30 KB = 2,150,400 B). */
    const val MAX_COMPRESSED_CHUNK_BYTES = MAX_CHUNKS * CHUNK_PAYLOAD_SIZE

    /** Payload byte threshold below which compression is skipped. */
    const val COMPRESSION_THRESHOLD_BYTES = 512
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
    /** Returns true when [frame] is a bounded raw or compressed single frame. */
    fun isValidSingleFrame(frame: ByteArray): Boolean =
        frame.isNotEmpty() &&
            frame.size <= MAX_SINGLE_FRAME_BYTES &&
            (frame[0] == FLAG_RAW || frame[0] == FLAG_COMPRESSED)

    /**
     * Encodes a raw Protobuf byte array into one or more transport frames.
     *
     * @param payload Raw Protobuf bytes to encode (up to [MAX_RAW_PAYLOAD_BYTES]).
     * @param transferId Optional transfer identifier for multi-part frames (allocated only when chunking is needed).
     * @return List of 1 or more frames, each <= [CHUNK_PAYLOAD_SIZE] + [CHUNK_HEADER_SIZE].
     */
    fun encode(
        payload: ByteArray,
        transferId: Short? = null,
    ): List<ByteArray> {
        require(payload.size <= MAX_RAW_PAYLOAD_BYTES) {
            "Payload size ${payload.size} exceeds maximum raw payload bytes $MAX_RAW_PAYLOAD_BYTES"
        }

        val dataToSend = selectEncodedPayload(payload)
        if (dataToSend.size <= CHUNK_PAYLOAD_SIZE) {
            val singleFlag = if (dataToSend !== payload) FLAG_COMPRESSED else FLAG_RAW
            val single = ByteArray(1 + dataToSend.size)
            single[0] = singleFlag
            System.arraycopy(dataToSend, 0, single, 1, dataToSend.size)
            return listOf(single)
        }

        // Payload exceeds chunk size: split into chunk frames (compressed if beneficial, raw otherwise)
        val chunkFlag = if (dataToSend !== payload) FLAG_CHUNK_COMPRESSED else FLAG_CHUNK_RAW
        val totalBytes = dataToSend.size
        require(totalBytes <= MAX_COMPRESSED_CHUNK_BYTES) {
            "Payload size $totalBytes exceeds maximum chunk capacity $MAX_COMPRESSED_CHUNK_BYTES"
        }

        val totalChunks = ((totalBytes + CHUNK_PAYLOAD_SIZE - 1) / CHUNK_PAYLOAD_SIZE).toShort()
        require(totalChunks.toInt() in 1..MAX_CHUNKS) {
            "Total chunks $totalChunks exceeds maximum chunk count $MAX_CHUNKS"
        }
        val actualTransferId = transferId ?: nextTransferId()

        val chunks = ArrayList<ByteArray>(totalChunks.toInt())
        for (i in 0 until totalChunks.toInt()) {
            val offset = i * CHUNK_PAYLOAD_SIZE
            val len = minOf(CHUNK_PAYLOAD_SIZE, totalBytes - offset)
            val chunkBuf = ByteBuffer.allocate(CHUNK_HEADER_SIZE + len)
            chunkBuf.put(chunkFlag)
            chunkBuf.putShort(actualTransferId)
            chunkBuf.putShort(i.toShort())
            chunkBuf.putShort(totalChunks)
            chunkBuf.putInt(totalBytes)
            chunkBuf.put(dataToSend, offset, len)
            chunks.add(chunkBuf.array())
        }
        return chunks
    }

    /**
     * Encodes a v3 client-to-server payload without allocating chunk frames.
     *
     * @param payload Raw Protobuf bytes to encode (default capped at [MAX_RAW_C2S_PAYLOAD_BYTES]).
     * @param maxRawBytes Maximum allowed uncompressed payload size.
     */
    fun encodeSingle(
        payload: ByteArray,
        maxRawBytes: Int = MAX_RAW_C2S_PAYLOAD_BYTES,
    ): ByteArray {
        require(payload.size <= maxRawBytes) {
            "Payload size ${payload.size} exceeds maximum raw payload bytes $maxRawBytes"
        }

        val dataToSend = selectEncodedPayload(payload)
        require(dataToSend.size <= CHUNK_PAYLOAD_SIZE) {
            "Encoded payload size ${dataToSend.size} exceeds single-frame payload limit $CHUNK_PAYLOAD_SIZE"
        }

        val frame = ByteArray(1 + dataToSend.size)
        frame[0] = if (dataToSend !== payload) FLAG_COMPRESSED else FLAG_RAW
        System.arraycopy(dataToSend, 0, frame, 1, dataToSend.size)
        return frame
    }

    private fun selectEncodedPayload(payload: ByteArray): ByteArray {
        if (payload.size < COMPRESSION_THRESHOLD_BYTES) return payload

        val compressed = compress(payload)
        return compressed.takeIf { it.size < payload.size } ?: payload
    }

    /**
     * Decodes a single unfragmented frame ([FLAG_RAW] or [FLAG_COMPRESSED]).
     *
     * @param frame Raw single frame.
     * @param maxDecompressedBytes Maximum allowed decompressed payload bytes.
     * @throws IllegalArgumentException if the frame is empty, malformed, or is a chunked frame ([FLAG_CHUNK_RAW] or [FLAG_CHUNK_COMPRESSED]).
     */
    fun decodeSingle(
        frame: ByteArray,
        maxDecompressedBytes: Int = MAX_RAW_PAYLOAD_BYTES,
    ): ByteArray {
        require(frame.isNotEmpty()) { "Cannot decode empty framed payload" }
        require(frame.size <= MAX_SINGLE_FRAME_BYTES) {
            "Single frame size ${frame.size} exceeds maximum $MAX_SINGLE_FRAME_BYTES"
        }
        return when (val flag = frame[0]) {
            FLAG_RAW -> {
                val result = ByteArray(frame.size - 1)
                System.arraycopy(frame, 1, result, 0, result.size)
                result
            }

            FLAG_COMPRESSED -> {
                decompress(frame, 1, frame.size - 1, maxDecompressedBytes = maxDecompressedBytes)
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
     * - If [FLAG_RAW] or [FLAG_COMPRESSED]: decodes and returns raw Protobuf bytes (bounded by [MAX_RAW_C2S_PAYLOAD_BYTES]).
     * - If [FLAG_CHUNK_RAW] or [FLAG_CHUNK_COMPRESSED]: throws [IllegalArgumentException] (chunked C2S frames are forbidden).
     * - Otherwise: treated as legacy un-framed Wire Protobuf bytes (bounded by [MAX_LEGACY_C2S_PAYLOAD_BYTES]).
     */
    fun unwrapServerInbound(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return raw
        return when (raw[0]) {
            FLAG_RAW, FLAG_COMPRESSED -> decodeSingle(raw, maxDecompressedBytes = MAX_RAW_C2S_PAYLOAD_BYTES)
            FLAG_CHUNK_RAW, FLAG_CHUNK_COMPRESSED -> throw IllegalArgumentException("Unexpected chunked frame in C2S packet")
            else -> {
                require(raw.size <= MAX_LEGACY_C2S_PAYLOAD_BYTES) {
                    "Legacy C2S payload size ${raw.size} exceeds maximum $MAX_LEGACY_C2S_PAYLOAD_BYTES"
                }
                raw
            }
        }
    }

    private val deflaterCache = ThreadLocal.withInitial { Deflater(Deflater.BEST_SPEED) }
    private val inflaterCache = ThreadLocal.withInitial { Inflater() }

    /**
     * Compress [data] using Deflate ([Deflater.BEST_SPEED]).
     */
    fun compress(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        val deflater = deflaterCache.get()
        deflater.reset()
        try {
            deflater.setInput(data, offset, length)
            deflater.finish()
            val initialCapacity = maxOf(4096, minOf(length, 65536))
            val output = ByteArrayOutputStream(initialCapacity)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            deflater.reset()
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
        maxDecompressedBytes: Int = MAX_RAW_PAYLOAD_BYTES,
    ): ByteArray {
        val inflater = inflaterCache.get()
        inflater.reset()
        try {
            inflater.setInput(data, offset, length)
            val initialCapacity = if (expectedSize > 0) {
                expectedSize
            } else {
                minOf(maxDecompressedBytes, maxOf(4096, length * 2))
            }
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
            inflater.reset()
        }
    }
}
