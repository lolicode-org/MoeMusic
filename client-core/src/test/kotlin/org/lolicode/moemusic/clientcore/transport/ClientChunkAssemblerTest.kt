package org.lolicode.moemusic.clientcore.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import java.util.Random

class ClientChunkAssemblerTest {

    @Test
    fun `process uncompressed raw frame returns decoded payload immediately`() {
        val assembler = ClientChunkAssembler()
        val payload = "Hello World".toByteArray()
        val frame = FramedPayloadCodec.encode(payload).first()

        val result = assembler.process(frame)
        assertNotNull(result)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `process compressed frame returns decompressed payload immediately`() {
        val assembler = ClientChunkAssembler()
        val payload = "MoeMusic Lyrics Test ".repeat(30).toByteArray()
        val frame = FramedPayloadCodec.encode(payload).first()
        assertEquals(FramedPayloadCodec.FLAG_COMPRESSED, frame[0])

        val result = assembler.process(frame)
        assertNotNull(result)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `process multi-chunk raw frames returns null until all chunks arrive and reassembles payload`() {
        val assembler = ClientChunkAssembler()
        val payload = ByteArray(80 * 1024).also { Random(123).nextBytes(it) }
        val frames = FramedPayloadCodec.encode(payload, transferId = 1)
        assertTrue(frames.size > 1)
        assertEquals(FramedPayloadCodec.FLAG_CHUNK_RAW, frames[0][0])

        for (i in 0 until frames.size - 1) {
            val intermediate = assembler.process(frames[i])
            assertNull(intermediate, "Intermediate chunk $i should return null")
        }

        val finalResult = assembler.process(frames.last())
        assertNotNull(finalResult, "Final chunk should complete assembly")
        assertArrayEquals(payload, finalResult)
    }

    @Test
    fun `process multi-chunk compressed frames returns null until all chunks arrive and decompresses payload`() {
        val assembler = ClientChunkAssembler()
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet")
        val random = Random(42)
        val sb = StringBuilder()
        while (sb.length < 200_000) {
            sb.append(words[random.nextInt(words.size)]).append(' ')
        }
        val payload = sb.toString().toByteArray()
        val frames = FramedPayloadCodec.encode(payload, transferId = 5)
        assertTrue(frames.size > 1)
        assertEquals(FramedPayloadCodec.FLAG_CHUNK_COMPRESSED, frames[0][0])

        for (i in 0 until frames.size - 1) {
            val intermediate = assembler.process(frames[i])
            assertNull(intermediate, "Intermediate chunk $i should return null")
        }

        val finalResult = assembler.process(frames.last())
        assertNotNull(finalResult, "Final chunk should complete assembly and decompression")
        assertArrayEquals(payload, finalResult)
    }

    @Test
    fun `process multi-chunk frames works with out-of-order arrival and duplicate chunks`() {
        val assembler = ClientChunkAssembler()
        val payload = ByteArray(80 * 1024).also { Random(456).nextBytes(it) }
        val frames = FramedPayloadCodec.encode(payload, transferId = 2)
        assertTrue(frames.size > 1)

        // Feed chunks in reverse order, with duplicate first frame
        val shuffled = frames.reversed()
        assertNull(assembler.process(shuffled[0]))
        assertNull(assembler.process(shuffled[0])) // duplicate

        for (i in 1 until shuffled.size - 1) {
            assertNull(assembler.process(shuffled[i]))
        }

        val result = assembler.process(shuffled.last())
        assertNotNull(result)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `process returns raw bytes as-is for legacy un-framed packets`() {
        val assembler = ClientChunkAssembler()
        val legacyWire = byteArrayOf(0x0A, 0x05, 0x65, 0x6E, 0x5F, 0x55, 0x53) // Protobuf field 1 tag
        val result = assembler.process(legacyWire)
        assertNotNull(result)
        assertArrayEquals(legacyWire, result)
    }

    @Test
    fun `evicts expired transfers when TTL expires`() {
        val shortTtlAssembler = ClientChunkAssembler(ttlNanos = 1_000_000L) // 1 ms
        val payload = ByteArray(80 * 1024).also { Random(789).nextBytes(it) }
        val frames = FramedPayloadCodec.encode(payload, transferId = 3)

        assertNull(shortTtlAssembler.process(frames[0]))
        Thread.sleep(10) // wait for TTL to expire

        // Send remaining chunks; the expired initial chunk was purged, so assembly should fail/restart
        for (i in 1 until frames.size) {
            shortTtlAssembler.process(frames[i])
        }
    }

    @Test
    fun `process successfully reassembles max 1MB incompressible payload across 35 chunks`() {
        val assembler = ClientChunkAssembler()
        val payload = ByteArray(FramedPayloadCodec.MAX_ASSEMBLED_BYTES).also { Random(123).nextBytes(it) }
        val frames = FramedPayloadCodec.encode(payload, transferId = 10)
        assertEquals(35, frames.size)

        for (i in 0 until frames.size - 1) {
            assertNull(assembler.process(frames[i]))
        }

        val reassembled = assembler.process(frames.last())
        assertNotNull(reassembled)
        assertArrayEquals(payload, reassembled)
    }

    @Test
    fun `process rejects malformed chunk bodies without buffering them`() {
        val assembler = ClientChunkAssembler()
        val payload = ByteArray(80 * 1024).also { Random(456).nextBytes(it) }
        val frames = FramedPayloadCodec.encode(payload, transferId = 20)

        // Modify first chunk by truncating its body
        val corruptedFrame = frames[0].copyOf(FramedPayloadCodec.CHUNK_HEADER_SIZE + 5)
        assertNull(assembler.process(corruptedFrame))
    }

    @Test
    fun `concurrent transfer admission strictly respects maxConcurrentTransfers`() {
        val assembler = ClientChunkAssembler(maxConcurrentTransfers = 2)
        val payload = ByteArray(80 * 1024).also { Random(789).nextBytes(it) }

        val transfer1 = FramedPayloadCodec.encode(payload, transferId = 1)
        val transfer2 = FramedPayloadCodec.encode(payload, transferId = 2)
        val transfer3 = FramedPayloadCodec.encode(payload, transferId = 3)

        assertNull(assembler.process(transfer1[0]))
        assertNull(assembler.process(transfer2[0]))
        // Exceeds maxConcurrentTransfers=2, transfer3 should be rejected immediately
        assertNull(assembler.process(transfer3[0]))

        // Complete transfer1
        for (i in 1 until transfer1.size) {
            val res = assembler.process(transfer1[i])
            if (i == transfer1.size - 1) assertNotNull(res)
        }

        // Now a slot is freed, new transfer should be admitted
        assertNull(assembler.process(transfer3[0]))
    }

    @Test
    fun `delayed callback from old transfer does not remove or replace active transfer with same transferId`() {
        val assembler = ClientChunkAssembler()
        val payload1 = ByteArray(70 * 1024).also { Random(111).nextBytes(it) }
        val payload2 = ByteArray(85 * 1024).also { Random(222).nextBytes(it) }

        val transfer1 = FramedPayloadCodec.encode(payload1, transferId = 42)
        val transfer2 = FramedPayloadCodec.encode(payload2, transferId = 42)

        // 1. Ingest first chunk of transfer1
        assertNull(assembler.process(transfer1[0]))

        // 2. Clear / evict old transfer
        assembler.clear()

        // 3. Start transfer2 reusing transferId=42
        assertNull(assembler.process(transfer2[0]))

        // 4. Delayed mismatched chunk 0 from old transfer1 arrives; must be dropped without replacing active transfer2
        assertNull(assembler.process(transfer1[0]))

        // 5. Delayed mismatched chunk 1 from old transfer1 arrives; must be dropped without removing transfer2
        assertNull(assembler.process(transfer1[1]))

        // 6. Complete transfer2; it must still be intact and reassemble successfully
        var result: ByteArray? = null
        for (i in 1 until transfer2.size) {
            result = assembler.process(transfer2[i])
        }
        assertNotNull(result)
        assertArrayEquals(payload2, result)
    }

    @Test
    fun `parallel chunk processing across multiple threads correctly reassembles concurrent transfers`() {
        val assembler = ClientChunkAssembler(maxConcurrentTransfers = 4)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val payloads = (1..4).map { id ->
                id to ByteArray(80 * 1024).also { Random(id.toLong()).nextBytes(it) }
            }
            val transfers = payloads.map { (id, payload) ->
                id to (payload to FramedPayloadCodec.encode(payload, transferId = id.toShort()))
            }

            val allFrames = transfers.flatMap { (id, pair) ->
                pair.second.map { frame -> id to frame }
            }.shuffled(Random(999))

            val results = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
            val futures = allFrames.map { (id, frame) ->
                executor.submit {
                    val res = assembler.process(frame)
                    if (res != null) {
                        results[id] = res
                    }
                }
            }

            futures.forEach { it.get() }

            assertEquals(4, results.size)
            transfers.forEach { (id, pair) ->
                assertArrayEquals(pair.first, results[id])
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `process drops chunks with mismatched compression flag for same transferId`() {
        val assembler = ClientChunkAssembler()
        val payload = ByteArray(80 * 1024).also { Random(777).nextBytes(it) }
        val rawFrames = FramedPayloadCodec.encode(payload, transferId = 33)
        assertEquals(FramedPayloadCodec.FLAG_CHUNK_RAW, rawFrames[0][0])

        assertNull(assembler.process(rawFrames[0]))

        // Create a conflicting frame with FLAG_CHUNK_COMPRESSED for the same transferId
        val conflictingFrame = rawFrames[1].clone()
        conflictingFrame[0] = FramedPayloadCodec.FLAG_CHUNK_COMPRESSED

        assertNull(assembler.process(conflictingFrame))

        // Complete original transfer with legitimate remaining frames
        var result: ByteArray? = null
        for (i in 1 until rawFrames.size) {
            result = assembler.process(rawFrames[i])
        }
        assertNotNull(result)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `process rejects raw chunk when totalBytes exceeds MAX_ASSEMBLED_BYTES`() {
        val assembler = ClientChunkAssembler()
        val oversizedTotalBytes = FramedPayloadCodec.MAX_ASSEMBLED_BYTES + 1000
        val totalChunks: Short = 35
        val buf = java.nio.ByteBuffer.allocate(FramedPayloadCodec.CHUNK_HEADER_SIZE + FramedPayloadCodec.CHUNK_PAYLOAD_SIZE)
        buf.put(FramedPayloadCodec.FLAG_CHUNK_RAW)
        buf.putShort(99.toShort()) // transferId
        buf.putShort(0.toShort())  // chunkIdx
        buf.putShort(totalChunks)
        buf.putInt(oversizedTotalBytes)
        buf.put(ByteArray(FramedPayloadCodec.CHUNK_PAYLOAD_SIZE))

        assertNull(assembler.process(buf.array()))
    }

    @Test
    fun `process drops oversized raw and compressed single frames`() {
        val assembler = ClientChunkAssembler()
        val oversizedRaw = ByteArray(FramedPayloadCodec.MAX_SINGLE_FRAME_BYTES + 1).also {
            it[0] = FramedPayloadCodec.FLAG_RAW
        }
        val oversizedCompressed = ByteArray(FramedPayloadCodec.MAX_SINGLE_FRAME_BYTES + 1).also {
            it[0] = FramedPayloadCodec.FLAG_COMPRESSED
        }

        assertNull(assembler.process(oversizedRaw))
        assertNull(assembler.process(oversizedCompressed))
    }

    @Test
    fun `process drops oversized legacy S2C payload`() {
        val assembler = ClientChunkAssembler()
        val oversized = ByteArray(FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES + 1) { 0x7F }

        assertNull(assembler.process(oversized))
    }
}
