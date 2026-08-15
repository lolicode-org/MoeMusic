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
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import java.util.UUID
import java.nio.ByteBuffer
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
    @Test
    fun `packet registry enforces the peer declared protocol framing`() {
        val v3User = TestUser(displayName = "V3Player", id = UUID.randomUUID())
        val v2User = TestUser(displayName = "V2Player", id = UUID.randomUUID())
        val handled = AtomicInteger(0)
        val rawPayload = byteArrayOf(0x0A, 0x01, 0x78)
        val framedPayload = FramedPayloadCodec.encode(rawPayload).single()

        registry.register(PacketIds.SYNC_REQUEST, { it }, { _, _ -> handled.incrementAndGet() })

        UserSessionRegistry.activate(v3User, protocolVersion = MoeMusicProtocol.VERSION)
        registry.dispatch(PacketIds.SYNC_REQUEST, rawPayload, v3User)
        assertEquals(0, handled.get())
        registry.dispatch(PacketIds.SYNC_REQUEST, framedPayload, v3User)
        assertEquals(1, handled.get())

        UserSessionRegistry.activate(v2User, protocolVersion = 2)
        registry.dispatch(PacketIds.SYNC_REQUEST, framedPayload, v2User)
        assertEquals(1, handled.get())
        registry.dispatch(PacketIds.SYNC_REQUEST, rawPayload, v2User)
        assertEquals(2, handled.get())
    }
    @Test
    fun `initial client handshake accepts only the unframed negotiation form`() {
        val user = TestUser(displayName = "HandshakePlayer", id = UUID.randomUUID())
        val handled = AtomicInteger(0)
        val handshake = ClientHandshake(
            protocol_version = MoeMusicProtocol.VERSION,
            mod_version = "1.4.0",
            locale = "en_us",
            initial_state = ClientStateProto.CLIENT_STATE_ACTIVE,
            client_send_monotonic = 1000L,
        ).encode()

        registry.register(PacketIds.CLIENT_HANDSHAKE, { it }, { _, _ -> handled.incrementAndGet() })
        registry.dispatch(PacketIds.CLIENT_HANDSHAKE, FramedPayloadCodec.encode(handshake).single(), user)
        assertEquals(0, handled.get())
        registry.dispatch(PacketIds.CLIENT_HANDSHAKE, handshake, user)
        assertEquals(1, handled.get())
    }

    @Test
    fun `client to server chunk frames are rejected even for v3 peers`() {
        val user = TestUser(displayName = "ChunkingPlayer", id = UUID.randomUUID())
        val handled = AtomicInteger(0)
        val rawPayload = byteArrayOf(0x0A, 0x01, 0x78)
        val chunkedPayload = ByteBuffer.allocate(FramedPayloadCodec.CHUNK_HEADER_SIZE + 1)
            .put(FramedPayloadCodec.FLAG_CHUNK_RAW)
            .putShort(0)
            .putShort(0)
            .putShort(1)
            .putInt(1)
            .put(0x0A)
            .array()

        registry.register(PacketIds.SYNC_REQUEST, { it }, { _, _ -> handled.incrementAndGet() })
        UserSessionRegistry.activate(user, protocolVersion = MoeMusicProtocol.VERSION)
        registry.dispatch(PacketIds.SYNC_REQUEST, chunkedPayload, user)
        assertEquals(0, handled.get())
        registry.dispatch(PacketIds.SYNC_REQUEST, FramedPayloadCodec.encode(rawPayload).single(), user)
        assertEquals(1, handled.get())
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
