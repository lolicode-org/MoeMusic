package org.lolicode.moemusic.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.protocol.proto.ClientHandshake
import org.lolicode.moemusic.core.protocol.proto.ClientStateProto
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.testing.TestUser
import org.lolicode.moemusic.core.transport.NetworkChannel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.parallel.ResourceLock
import org.lolicode.moemusic.core.plugin.PluginManager

@ResourceLock("PluginManager")
class ServerPacketHandlersTest {

    private lateinit var channel: TestNetworkChannel
    private lateinit var sessionBridge: TestSessionBridge
    private lateinit var registry: PacketRegistry
    private lateinit var handlers: ServerPacketHandlers

    @BeforeEach
    fun setUp() {
        UserSessionRegistry.clear()
        PluginManager.reset()
        channel = TestNetworkChannel()
        sessionBridge = TestSessionBridge()
        registry = PacketRegistry()
        handlers = ServerPacketHandlers(channel, sessionBridge)
        handlers.registerAll(registry)
    }

    @AfterEach
    fun tearDown() {
        UserSessionRegistry.clear()
        PluginManager.reset()
    }

    @Test
    fun `legacy v2 client triggers notifyOutdatedClient on handshake`() {
        val user = TestUser(displayName = "LegacyPlayer", id = UUID.randomUUID())
        val handshake = ClientHandshake(
            protocol_version = 2,
            mod_version = "1.3.0",
            locale = "en_us",
            initial_state = ClientStateProto.CLIENT_STATE_ACTIVE,
            client_send_monotonic = 1000L,
        )

        registry.dispatch(PacketIds.CLIENT_HANDSHAKE, handshake.encode(), user)

        assertEquals(1, sessionBridge.outdatedNotificationCount.get())
        assertEquals(2, sessionBridge.lastReportedProtocolVersion.get())
        assertTrue(channel.sentPackets.any { it.packetId == PacketIds.SERVER_WELCOME })
    }

    @Test
    fun `modern v3 client does not trigger notifyOutdatedClient`() {
        val user = TestUser(displayName = "ModernPlayer", id = UUID.randomUUID())
        val handshake = ClientHandshake(
            protocol_version = MoeMusicProtocol.VERSION,
            mod_version = "1.4.0",
            locale = "en_us",
            initial_state = ClientStateProto.CLIENT_STATE_ACTIVE,
            client_send_monotonic = 1000L,
        )

        registry.dispatch(PacketIds.CLIENT_HANDSHAKE, handshake.encode(), user)

        assertEquals(0, sessionBridge.outdatedNotificationCount.get())
        assertTrue(channel.sentPackets.any { it.packetId == PacketIds.SERVER_WELCOME })
    }

    private class TestSessionBridge : ServerPacketSessionBridge {
        val outdatedNotificationCount = AtomicInteger(0)
        val lastReportedProtocolVersion = AtomicInteger(-1)

        override fun activate(sender: MoeMusicUser, locale: String, protocolVersion: Int): MoeMusicUser {
            UserSessionRegistry.upsert(sender, locale, UserSessionRegistry.Participation.ACTIVE, protocolVersion)
            return sender
        }

        override fun standby(sender: MoeMusicUser, locale: String, protocolVersion: Int): MoeMusicUser {
            UserSessionRegistry.upsert(sender, locale, UserSessionRegistry.Participation.STANDBY, protocolVersion)
            return sender
        }

        override fun handleRegisteredClientLeave(userId: UUID) {}

        override fun notifyOutdatedClient(user: MoeMusicUser, clientProtocolVersion: Int) {
            outdatedNotificationCount.incrementAndGet()
            lastReportedProtocolVersion.set(clientProtocolVersion)
        }
    }

    private class TestNetworkChannel : NetworkChannel {
        data class SentPacket(val user: MoeMusicUser?, val packetId: PacketId, val payload: ByteArray)

        val sentPackets = mutableListOf<SentPacket>()

        override fun sendToServer(packetId: PacketId, payload: ByteArray) {}

        override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) {
            sentPackets.add(SentPacket(user, packetId, payload))
        }

        override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
            sentPackets.add(SentPacket(null, packetId, payload))
        }
    }
}
