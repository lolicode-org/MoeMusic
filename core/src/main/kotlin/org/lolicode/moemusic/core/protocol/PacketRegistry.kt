package org.lolicode.moemusic.core.protocol

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Central dispatcher for inbound packets.
 *
 * Platform adapters receive raw bytes from the network layer and call [dispatch].
 * `:core` internals register typed handlers via [register].
 *
 * There is exactly one handler per [PacketId]. Registering a second handler for the
 * same ID replaces the first and logs a warning.
 */
class PacketRegistry {

    private val logger = LoggerFactory.getLogger(PacketRegistry::class.java)

    private data class Entry<T>(
        val decoder: (ByteArray) -> T,
        val handler: (T, MoeMusicUser?) -> Unit,
    )

    private val entries = ConcurrentHashMap<PacketId, Entry<*>>()

    /**
     * Register a handler for packets arriving on [id].
     *
     * @param id      The [PacketId] this handler owns.
     * @param decoder Converts raw Wire-encoded bytes to a typed message [T].
     * @param handler Called with the decoded message and the sending [MoeMusicUser]
     *                (null when received on the client side from the server).
     */
    fun <T : Any> register(
        id: PacketId,
        decoder: (ByteArray) -> T,
        handler: (T, MoeMusicUser?) -> Unit,
    ) {
        val prev = entries.put(id, Entry(decoder, handler))
        if (prev != null) {
            logger.warn("PacketRegistry: handler for {} was replaced", id)
        }
    }

    /**
     * Decode [raw] bytes and invoke the registered handler for [id].
     *
     * Decoding and handler exceptions are caught, logged, and swallowed so that one
     * bad packet does not disconnect the player or crash the server.
     *
     * @param id     Identifies which handler to invoke.
     * @param raw    Wire-encoded protobuf bytes.
     * @param sender The player who sent the packet, or null on the client side.
     */
    @Suppress("UNCHECKED_CAST")
    fun dispatch(id: PacketId, raw: ByteArray, sender: MoeMusicUser?) {
        if (sender != null && !acceptsClientPayload(id, raw, sender)) {
            logger.debug(
                "PacketRegistry: dropping {} from {} because its payload does not match the declared protocol version",
                id,
                sender.displayName,
            )
            return
        }
        val entry = entries[id] as? Entry<Any>
        if (entry == null) {
            logger.debug("PacketRegistry: no handler for {}, dropping packet", id)
            return
        }
        try {
            val message = entry.decoder(raw)
            entry.handler(message, sender)
        } catch (e: Exception) {
            logger.error("PacketRegistry: error handling packet {} from {}: {}", id, sender?.displayName ?: "server", e.message, e)
        }
    }

    private fun acceptsClientPayload(id: PacketId, raw: ByteArray, sender: MoeMusicUser): Boolean =
        ProtocolPayloadValidator.acceptsClientToServer(
            packetId = id,
            payload = raw,
            declaredProtocolVersion = UserSessionRegistry.session(sender.id)?.protocolVersion,
        )

    /** Returns true if a handler is registered for [id]. */
    fun isRegistered(id: PacketId): Boolean = entries.containsKey(id)
}
