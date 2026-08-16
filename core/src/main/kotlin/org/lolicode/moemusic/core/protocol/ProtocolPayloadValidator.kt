package org.lolicode.moemusic.core.protocol

import org.lolicode.moemusic.core.protocol.proto.ServerWelcome
import org.lolicode.moemusic.core.transport.FramedPayloadCodec

/**
 * Validates the transport representation selected by the peer's negotiated protocol version.
 *
 * Protocol v2 uses legacy unframed protobuf payloads. Protocol v3 uses a framing flag for every
 * established-session packet; only server-to-client traffic may use chunk frames. The initial
 * client handshake is always unframed so a v3 client can negotiate down with a v2 server.
 */
object ProtocolPayloadValidator {
    /**
     * Validate an inbound client-to-server payload before decoding it.
     *
     * [declaredProtocolVersion] is null until the peer has completed a handshake. The handshake
     * itself remains the one unframed negotiation packet and is bounded by the legacy C2S limit.
     */
    fun acceptsClientToServer(
        packetId: PacketId,
        payload: ByteArray,
        declaredProtocolVersion: Int?,
    ): Boolean {
        if (packetId == PacketIds.CLIENT_HANDSHAKE) {
            return !FramedPayloadCodec.isFramed(payload) &&
                payload.size <= FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES
        }
        val protocolVersion = declaredProtocolVersion ?: return false
        return matchesEstablishedProtocol(payload, protocolVersion, allowChunking = false)
    }

    /**
     * Validate an inbound server-to-client payload before decoding it.
     *
     * Before the server welcome is accepted, a v3 peer may receive either the raw rejection used
     * for no-session negotiation or the framed accepted welcome sent after server registration.
     * The decoded welcome is checked separately by [acceptsServerWelcome].
     */
    fun acceptsServerToClient(
        packetId: PacketId,
        payload: ByteArray,
        activeProtocolVersion: Int,
        serverSessionAccepted: Boolean,
    ): Boolean {
        if (packetId == PacketIds.SERVER_WELCOME && !serverSessionAccepted) {
            if (!FramedPayloadCodec.isFramed(payload)) {
                return payload.size <= FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES
            }
            return activeProtocolVersion >= 3 &&
                matchesEstablishedProtocol(payload, activeProtocolVersion, allowChunking = true)
        }
        if (!serverSessionAccepted) return false
        return matchesEstablishedProtocol(payload, activeProtocolVersion, allowChunking = true)
    }

    /**
     * Check that a decoded [welcome] used the representation required by the handshake state.
     *
     * A rejected no-session welcome is raw even when the client initially offered v3. An accepted
     * v3 welcome is the first established-session packet and therefore must be framed.
     */
    fun acceptsServerWelcome(
        payload: ByteArray,
        welcome: ServerWelcome,
        activeProtocolVersion: Int,
        serverSessionAccepted: Boolean,
    ): Boolean {
        if (serverSessionAccepted) {
            return matchesEstablishedProtocol(payload, activeProtocolVersion, allowChunking = true)
        }
        if (!welcome.accepted) {
            return !FramedPayloadCodec.isFramed(payload) &&
                payload.size <= FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES
        }
        return if (activeProtocolVersion >= 3) {
            matchesEstablishedProtocol(payload, activeProtocolVersion, allowChunking = true)
        } else {
            !FramedPayloadCodec.isFramed(payload) &&
                payload.size <= FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES
        }
    }

    /**
     * Validate an established-session payload against [protocolVersion].
     *
     * Client-to-server chunking is disabled by passing `false`; server-to-client chunking is
     * allowed by passing `true`.
     */
    fun matchesEstablishedProtocol(
        payload: ByteArray,
        protocolVersion: Int,
        allowChunking: Boolean,
    ): Boolean {
        if (protocolVersion >= 3) {
            if (!FramedPayloadCodec.isFramed(payload)) return false
            return if (FramedPayloadCodec.isChunk(payload[0])) {
                allowChunking && payload.size <= FramedPayloadCodec.MAX_CHUNK_FRAME_BYTES
            } else {
                FramedPayloadCodec.isValidSingleFrame(payload)
            }
        }

        return !FramedPayloadCodec.isFramed(payload) &&
            payload.size <= if (allowChunking) {
                FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES
            } else {
                FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES
            }
    }
}
