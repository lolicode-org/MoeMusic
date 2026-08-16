package org.lolicode.moemusic.core.protocol

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lolicode.moemusic.core.protocol.proto.ClientStateProto
import org.lolicode.moemusic.core.protocol.proto.ServerWelcome
import org.lolicode.moemusic.core.protocol.proto.ServerWelcomeRejectReason
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import java.nio.ByteBuffer

class ProtocolPayloadValidatorTest {
    @Test
    fun `v2 C2S raw payload uses the directional legacy ceiling`() {
        assertTrue(
            ProtocolPayloadValidator.matchesEstablishedProtocol(
                legacyPayload(FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES),
                protocolVersion = 2,
                allowChunking = false,
            ),
        )
        assertFalse(
            ProtocolPayloadValidator.matchesEstablishedProtocol(
                legacyPayload(FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES + 1),
                protocolVersion = 2,
                allowChunking = false,
            ),
        )
    }

    @Test
    fun `v2 S2C raw payload uses the 1 MiB legacy ceiling`() {
        assertTrue(
            ProtocolPayloadValidator.matchesEstablishedProtocol(
                legacyPayload(FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES),
                protocolVersion = 2,
                allowChunking = true,
            ),
        )
        assertFalse(
            ProtocolPayloadValidator.matchesEstablishedProtocol(
                legacyPayload(FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES + 1),
                protocolVersion = 2,
                allowChunking = true,
            ),
        )
    }

    @Test
    fun `v3 single frames use the encoded single-frame ceiling`() {
        val exact = singleFrame(FramedPayloadCodec.CHUNK_PAYLOAD_SIZE)
        val oversized = singleFrame(FramedPayloadCodec.CHUNK_PAYLOAD_SIZE + 1)

        assertTrue(ProtocolPayloadValidator.matchesEstablishedProtocol(exact, 3, allowChunking = false))
        assertFalse(ProtocolPayloadValidator.matchesEstablishedProtocol(oversized, 3, allowChunking = false))
    }

    @Test
    fun `v3 S2C chunk frames use the encoded chunk-frame ceiling`() {
        val exact = chunkFrame(FramedPayloadCodec.MAX_CHUNK_FRAME_BYTES)
        val oversized = chunkFrame(FramedPayloadCodec.MAX_CHUNK_FRAME_BYTES + 1)

        assertTrue(ProtocolPayloadValidator.matchesEstablishedProtocol(exact, 3, allowChunking = true))
        assertFalse(ProtocolPayloadValidator.matchesEstablishedProtocol(oversized, 3, allowChunking = true))
        assertFalse(ProtocolPayloadValidator.matchesEstablishedProtocol(exact, 3, allowChunking = false))
    }

    @Test
    fun `initial handshake remains unframed and uses the C2S ceiling`() {
        val exact = legacyPayload(FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES)
        val oversized = legacyPayload(FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES + 1)

        assertTrue(ProtocolPayloadValidator.acceptsClientToServer(PacketIds.CLIENT_HANDSHAKE, exact, null))
        assertFalse(ProtocolPayloadValidator.acceptsClientToServer(PacketIds.CLIENT_HANDSHAKE, oversized, null))
        assertFalse(
            ProtocolPayloadValidator.acceptsClientToServer(
                PacketIds.CLIENT_HANDSHAKE,
                FramedPayloadCodec.encodeSingle(byteArrayOf(0x7F)),
                null,
            ),
        )
    }

    @Test
    fun `pre-session rejected raw welcome uses the S2C ceiling`() {
        val exact = legacyPayload(FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES)
        val oversized = legacyPayload(FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES + 1)
        val rejected = rejectedWelcome()

        assertTrue(
            ProtocolPayloadValidator.acceptsServerToClient(
                PacketIds.SERVER_WELCOME,
                exact,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
        assertFalse(
            ProtocolPayloadValidator.acceptsServerToClient(
                PacketIds.SERVER_WELCOME,
                oversized,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
        assertTrue(
            ProtocolPayloadValidator.acceptsServerWelcome(
                exact,
                rejected,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
        assertFalse(
            ProtocolPayloadValidator.acceptsServerWelcome(
                oversized,
                rejected,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
    }

    @Test
    fun `accepted v3 welcomes require bounded framed representation`() {
        val accepted = acceptedWelcome()
        val single = singleFrame(1)
        val chunk = chunkFrame(FramedPayloadCodec.CHUNK_HEADER_SIZE + 1)

        assertTrue(
            ProtocolPayloadValidator.acceptsServerToClient(
                PacketIds.SERVER_WELCOME,
                single,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
        assertTrue(
            ProtocolPayloadValidator.acceptsServerWelcome(
                single,
                accepted,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
        assertTrue(
            ProtocolPayloadValidator.acceptsServerToClient(
                PacketIds.SERVER_WELCOME,
                chunk,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
        assertTrue(
            ProtocolPayloadValidator.acceptsServerWelcome(
                chunk,
                accepted,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
        assertFalse(
            ProtocolPayloadValidator.acceptsServerWelcome(
                legacyPayload(1),
                accepted,
                activeProtocolVersion = 3,
                serverSessionAccepted = false,
            ),
        )
    }

    @Test
    fun `accepted v2 welcome remains bounded unframed S2C traffic`() {
        val accepted = acceptedWelcome()
        val exact = legacyPayload(FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES)
        val oversized = legacyPayload(FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES + 1)

        assertTrue(
            ProtocolPayloadValidator.acceptsServerWelcome(
                exact,
                accepted,
                activeProtocolVersion = 2,
                serverSessionAccepted = false,
            ),
        )
        assertFalse(
            ProtocolPayloadValidator.acceptsServerWelcome(
                oversized,
                accepted,
                activeProtocolVersion = 2,
                serverSessionAccepted = false,
            ),
        )
    }

    @Test
    fun `C2S chunk frames remain rejected before decoding`() {
        val chunk = chunkFrame(FramedPayloadCodec.CHUNK_HEADER_SIZE + 1)

        assertFalse(
            ProtocolPayloadValidator.acceptsClientToServer(
                PacketIds.SYNC_REQUEST,
                chunk,
                declaredProtocolVersion = 3,
            ),
        )
        assertFalse(ProtocolPayloadValidator.matchesEstablishedProtocol(chunk, 3, allowChunking = false))
    }

    private fun legacyPayload(size: Int): ByteArray = ByteArray(size) { 0x7F }

    private fun singleFrame(bodySize: Int): ByteArray = ByteArray(bodySize + 1).also {
        it[0] = FramedPayloadCodec.FLAG_RAW
    }

    private fun chunkFrame(size: Int): ByteArray {
        val bodySize = size - FramedPayloadCodec.CHUNK_HEADER_SIZE
        return ByteBuffer.allocate(size)
            .put(FramedPayloadCodec.FLAG_CHUNK_RAW)
            .putShort(1)
            .putShort(0)
            .putShort(1)
            .putInt(bodySize)
            .put(ByteArray(bodySize))
            .array()
    }

    private fun rejectedWelcome() = ServerWelcome(
        accepted = false,
        failure = "rejected",
        server_protocol_version = 3,
        accepted_state = ClientStateProto.CLIENT_STATE_ACTIVE,
        reject_reason = ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_PROTOCOL_MISMATCH,
    )

    private fun acceptedWelcome() = ServerWelcome(
        accepted = true,
        server_protocol_version = 3,
        accepted_state = ClientStateProto.CLIENT_STATE_ACTIVE,
    )
}
