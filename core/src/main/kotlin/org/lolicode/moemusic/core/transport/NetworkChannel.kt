package org.lolicode.moemusic.core.transport

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.protocol.PacketId

/**
 * Platform-agnostic packet sending abstraction.
 *
 * Implemented once per platform (for example `BadPacketsNetworkChannel` in `:platform-common`).
 * `:core` depends only on this interface — no Minecraft or Fabric types appear here.
 *
 * Implementations must be thread-safe; core components may call these methods from
 * background threads (e.g. search coroutines, LavaPlayer callbacks).
 */
interface NetworkChannel {

    /**
     * Send a raw payload to the server on [packetId].
     * Only meaningful on the **client** side.
     *
     * @param packetId The channel to send on.
     * @param payload  Wire-encoded protobuf bytes.
     */
    fun sendToServer(packetId: PacketId, payload: ByteArray)

    /**
     * Send a raw payload to a single [user].
     * Only meaningful on the **server** side.
     *
     * Implementations should deliver these packets only when either:
     * 1. [user] is an actively handshaked/registered client for this server session, or
     * 2. [packetId] is an explicit direct response to that client's own request.
     *
     * Unsolicited playback/state packets must not be sent to unregistered or standby clients.
     *
     * @param user    The target client.
     * @param packetId The channel to send on.
     * @param payload  Wire-encoded protobuf bytes.
     */
    fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray)

    /**
     * Broadcast a raw payload to **all** currently connected compatible clients.
     * Only meaningful on the **server** side.
     *
     * @param packetId The channel to send on.
     * @param payload  Wire-encoded protobuf bytes.
     */
    fun sendToAllClients(packetId: PacketId, payload: ByteArray)
}
