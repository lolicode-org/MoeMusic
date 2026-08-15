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
     * itself remains the one unframed negotiation packet and is accepted without a declaration.
     */
    fun acceptsClientToServer(
        packetId: PacketId,
        payload: ByteArray,
        declaredProtocolVersion: Int?,
    ): Boolean {
        if (packetId == PacketIds.CLIENT_HANDSHAKE) {
            return !FramedPayloadCodec.isFramed(payload)
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
            return activeProtocolVersion >= 3 || !FramedPayloadCodec.isFramed(payload)
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
        val framed = FramedPayloadCodec.isFramed(payload)
        if (serverSessionAccepted) {
            return matchesEstablishedProtocol(payload, activeProtocolVersion, allowChunking = true)
        }
        if (!welcome.accepted) return !framed
        return (activeProtocolVersion >= 3) == framed
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
        val framed = FramedPayloadCodec.isFramed(payload)
        if (protocolVersion >= 3) {
            if (!framed) return false
            return allowChunking || !FramedPayloadCodec.isChunk(payload[0])
        }
        return !framed
    }
}
