package org.lolicode.moemusic.core.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FramedPayloadCodecTest {

    @Test
    fun `small payload is encoded as raw single frame`() {
        val payload = "Hello MoeMusic".toByteArray()
        val frames = FramedPayloadCodec.encode(payload)
        assertEquals(1, frames.size)
        assertEquals(FramedPayloadCodec.FLAG_RAW, frames[0][0])
        val decoded = FramedPayloadCodec.decodeSingle(frames[0])
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun `compressible payload is encoded as compressed single frame`() {
        // Build a repetitive string at least 512 bytes long
        val text = "MoeMusic Synchronized Lyrics Line Timestamp [00:12.345] Translation ".repeat(10)
        val payload = text.toByteArray()
        assertTrue(payload.size >= FramedPayloadCodec.COMPRESSION_THRESHOLD_BYTES)

        val frames = FramedPayloadCodec.encode(payload)
        assertEquals(1, frames.size)
        assertEquals(FramedPayloadCodec.FLAG_COMPRESSED, frames[0][0])
        assertTrue(frames[0].size < payload.size, "Compressed frame should be smaller than original")

        val decoded = FramedPayloadCodec.decodeSingle(frames[0])
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun `compression starts at the 512-byte threshold`() {
        val belowThreshold = ByteArray(511) { (it % 4).toByte() }
        val atThreshold = ByteArray(512) { (it % 4).toByte() }

        val rawFrame = FramedPayloadCodec.encodeSingle(belowThreshold)
        val compressedFrame = FramedPayloadCodec.encodeSingle(atThreshold)

        assertEquals(FramedPayloadCodec.FLAG_RAW, rawFrame[0])
        assertEquals(FramedPayloadCodec.FLAG_COMPRESSED, compressedFrame[0])
        assertArrayEquals(belowThreshold, FramedPayloadCodec.decodeSingle(rawFrame))
        assertArrayEquals(atThreshold, FramedPayloadCodec.decodeSingle(compressedFrame))
    }

    @Test
    fun `large incompressible payload exceeding chunk size is split into multiple raw chunk frames`() {
        // Create an uncompressible random payload exceeding 30 KB
        val largeData = ByteArray(80 * 1024).also { java.util.Random(42).nextBytes(it) }
        val transferId: Short = 42
        val frames = FramedPayloadCodec.encode(largeData, transferId = transferId)

        assertTrue(frames.size > 1, "Should produce multiple chunk frames")
        for ((index, frame) in frames.withIndex()) {
            assertEquals(FramedPayloadCodec.FLAG_CHUNK_RAW, frame[0])
            assertTrue(frame.size <= FramedPayloadCodec.CHUNK_PAYLOAD_SIZE + FramedPayloadCodec.CHUNK_HEADER_SIZE)
        }
    }

    @Test
    fun `large compressible payload exceeding chunk size is split into multiple compressed chunk frames`() {
        // Build moderately compressible payload (~200 KB) that compresses to ~40 KB (> 30 KB CHUNK_PAYLOAD_SIZE)
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet")
        val random = java.util.Random(42)
        val sb = StringBuilder()
        while (sb.length < 200_000) {
            sb.append(words[random.nextInt(words.size)]).append(' ')
        }
        val largeCompressible = sb.toString().toByteArray()

        val frames = FramedPayloadCodec.encode(largeCompressible, transferId = 99)
        assertTrue(frames.size > 1, "Should produce multiple chunk frames")
        for (frame in frames) {
            assertEquals(FramedPayloadCodec.FLAG_CHUNK_COMPRESSED, frame[0])
            assertTrue(frame.size <= FramedPayloadCodec.CHUNK_PAYLOAD_SIZE + FramedPayloadCodec.CHUNK_HEADER_SIZE)
        }
    }

    @Test
    fun `decodeSingle rejects chunked frames and malformed frame`() {
        val rawChunkFrame = ByteArray(FramedPayloadCodec.CHUNK_HEADER_SIZE + 10)
        rawChunkFrame[0] = FramedPayloadCodec.FLAG_CHUNK_RAW

        val compressedChunkFrame = ByteArray(FramedPayloadCodec.CHUNK_HEADER_SIZE + 10)
        compressedChunkFrame[0] = FramedPayloadCodec.FLAG_CHUNK_COMPRESSED

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.decodeSingle(rawChunkFrame)
        }

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.decodeSingle(compressedChunkFrame)
        }

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.decodeSingle(byteArrayOf())
        }

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.decodeSingle(byteArrayOf(0x7F))
        }
    }

    @Test
    fun `isChunk correctly identifies chunk flags`() {
        assertTrue(FramedPayloadCodec.isChunk(FramedPayloadCodec.FLAG_CHUNK_RAW))
        assertTrue(FramedPayloadCodec.isChunk(FramedPayloadCodec.FLAG_CHUNK_COMPRESSED))
        assertFalse(FramedPayloadCodec.isChunk(FramedPayloadCodec.FLAG_RAW))
        assertFalse(FramedPayloadCodec.isChunk(FramedPayloadCodec.FLAG_COMPRESSED))
        assertFalse(FramedPayloadCodec.isChunk(0x7F))
    }

    @Test
    fun `max advertised chunked payload of 2MB produces exactly 69 chunks and succeeds`() {
        val maxPayload = ByteArray(FramedPayloadCodec.MAX_RAW_PAYLOAD_BYTES).also {
            java.util.Random(123).nextBytes(it)
        }
        val frames = FramedPayloadCodec.encode(maxPayload)
        assertEquals(69, frames.size)
    }

    @Test
    fun `decompress rejects incomplete truncated deflate stream`() {
        val original = "Hello MoeMusic Transport Protection".repeat(10).toByteArray()
        val compressed = FramedPayloadCodec.compress(original)
        val truncated = compressed.copyOf(compressed.size / 2)

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.decompress(truncated)
        }
    }

    @Test
    fun `decompress bounds maximum decompressed output size to prevent deflate bombs`() {
        val bombSource = ByteArray(100_000) { 0 }
        val compressed = FramedPayloadCodec.compress(bombSource)

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.decompress(compressed, maxDecompressedBytes = 10_000)
        }
    }

    @Test
    fun `encode immediately rejects compressible payload exceeding MAX_RAW_PAYLOAD_BYTES even if compressed size is small`() {
        // 4 MB of zeros would compress to a tiny payload, but must be rejected before single-frame return
        val oversizedCompressible = ByteArray(2 * FramedPayloadCodec.MAX_RAW_PAYLOAD_BYTES) { 0 }

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.encode(oversizedCompressible)
        }
    }

    @Test
    fun `encodeSingle compresses large repetitive payload into one bounded frame`() {
        val payload = ByteArray(40 * 1024) { if (it % 2 == 0) 0 else 1 }

        val frame = FramedPayloadCodec.encodeSingle(payload)

        assertEquals(FramedPayloadCodec.FLAG_COMPRESSED, frame[0])
        assertTrue(frame.size <= FramedPayloadCodec.MAX_SINGLE_FRAME_BYTES)
        assertArrayEquals(payload, FramedPayloadCodec.decodeSingle(frame))
    }

    @Test
    fun `encodeSingle rejects incompressible payload that would require chunks`() {
        val payload = ByteArray(FramedPayloadCodec.CHUNK_PAYLOAD_SIZE + 1).also {
            java.util.Random(42).nextBytes(it)
        }

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.encodeSingle(payload)
        }
    }

    @Test
    fun `encodeSingle rejects payload exceeding MAX_RAW_C2S_PAYLOAD_BYTES`() {
        val oversized = ByteArray(FramedPayloadCodec.MAX_RAW_C2S_PAYLOAD_BYTES + 1) { 0 }

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.encodeSingle(oversized)
        }
    }

    @Test
    fun `unwrapServerInbound rejects C2S payload decompressing beyond MAX_RAW_C2S_PAYLOAD_BYTES`() {
        val bombSource = ByteArray(FramedPayloadCodec.MAX_RAW_C2S_PAYLOAD_BYTES + 1) { 0 }
        val compressed = FramedPayloadCodec.compress(bombSource)
        val frame = ByteArray(1 + compressed.size)
        frame[0] = FramedPayloadCodec.FLAG_COMPRESSED
        System.arraycopy(compressed, 0, frame, 1, compressed.size)

        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.unwrapServerInbound(frame)
        }
    }

    @Test
    fun `decodeSingle rejects oversized raw and compressed frames before decoding`() {
        val oversizedRaw = ByteArray(FramedPayloadCodec.MAX_SINGLE_FRAME_BYTES + 1).also {
            it[0] = FramedPayloadCodec.FLAG_RAW
        }
        val oversizedCompressed = ByteArray(FramedPayloadCodec.MAX_SINGLE_FRAME_BYTES + 1).also {
            it[0] = FramedPayloadCodec.FLAG_COMPRESSED
        }

        assertThrows<IllegalArgumentException> { FramedPayloadCodec.decodeSingle(oversizedRaw) }
        assertThrows<IllegalArgumentException> { FramedPayloadCodec.decodeSingle(oversizedCompressed) }
    }

    @Test
    fun `decompression accepts exactly 2 MiB and rejects one byte beyond`() {
        val exact = ByteArray(FramedPayloadCodec.MAX_RAW_PAYLOAD_BYTES) { 0 }
        val oversized = ByteArray(FramedPayloadCodec.MAX_RAW_PAYLOAD_BYTES + 1) { 0 }

        assertArrayEquals(exact, FramedPayloadCodec.decompress(FramedPayloadCodec.compress(exact)))
        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.decompress(FramedPayloadCodec.compress(oversized))
        }
    }

    @Test
    fun `legacy C2S payload accepts exact ceiling and rejects one byte beyond`() {
        val exact = ByteArray(FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES) { 0x7F }
        val oversized = ByteArray(FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES + 1) { 0x7F }

        assertArrayEquals(exact, FramedPayloadCodec.unwrapServerInbound(exact))
        assertThrows<IllegalArgumentException> {
            FramedPayloadCodec.unwrapServerInbound(oversized)
        }
    }
}
