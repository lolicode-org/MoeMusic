package org.lolicode.moemusic.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.MusicSource
import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.event.OnContentFilterRulesApplied
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.permission.MoeMusicPermission
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.pluginConfigSpec
import org.lolicode.moemusic.api.service.*
import org.lolicode.moemusic.core.plugin.PluginConfigIO
import org.lolicode.moemusic.core.plugin.resetPluginTestState
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.transport.NetworkChannel
import java.nio.file.Files
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ServerRuntimeCoordinatorTest {

    @BeforeTest
    fun resetStateBeforeTest() {
        resetPluginTestState()
    }

    @AfterTest
    fun resetStateAfterTest() {
        ServerRuntimeCoordinator.serverShutdown(finalRuntime = true)
        resetPluginTestState()
    }

    @Test
    fun `server shutdown cancels session packet tasks`() = runBlocking {
        val configDir = Files.createTempDirectory("moemusic-session-task-cancel")
        try {
            ServerRuntimeCoordinator.serverInit(
                channel = NoopNetworkChannel,
                configDir = configDir,
                pluginServicesFactory = { _, _, _ -> testPluginServices },
            )
            val started = CompletableDeferred<Unit>()
            val task = ServerRuntimeCoordinator.launchServerSessionTask {
                started.complete(Unit)
                awaitCancellation()
            }

            withTimeout(3_000) { started.await() }
            ServerRuntimeCoordinator.serverShutdown(finalRuntime = false)
            withTimeout(3_000) { task.join() }

            assertTrue(task.isCancelled)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reloadServerConfigFromDisk notifies plugin configs before filter and autoplay refresh`() {
        @Serializable
        data class TestConfig(val playlistId: String = "old-playlist")

        val events = Collections.synchronizedList(mutableListOf<String>())
        val activePlaylistId = AtomicReference("old-playlist")
        val recordReloadFetch = AtomicBoolean(false)
        val initialFetch = CountDownLatch(1)
        val reloadFetch = CountDownLatch(1)

        val source = object : MusicSource {
            override val id = "test-reload-order-source"

            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution =
                PlaybackResolution(PlaybackResource("https://example.test/${track.id}"))

            override suspend fun getAutoplayTracks(): List<TrackInfo> {
                val playlistId = activePlaylistId.get()
                if (recordReloadFetch.get()) {
                    events += "autoplay:$playlistId"
                    reloadFetch.countDown()
                } else {
                    initialFetch.countDown()
                }
                return listOf(
                    TrackInfo(id = playlistId, title = "Track $playlistId", artists = listOf("Artist").toArtistInfos(), durationMs = 60_000) { sourceId = "test-reload-order-source" }
                )
            }
        }

        val plugin = object : Plugin {
            override val id = "test-reload-order-plugin-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
            override val configSpec = pluginConfigSpec(::TestConfig) {
                string(
                    "playlist_id",
                    { it.playlistId },
                    updater = { config, value -> config.copy(playlistId = value) },
                )
            }

            override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
                activePlaylistId.set(ctx.loadConfig(configSpec).playlistId)
                ctx.registerMusicSource(source)
                ctx.eventBus.subscribe(OnContentFilterRulesApplied::class.java) {
                    events += "filter"
                }
                ctx.onConfigChanged(configSpec) { updated ->
                    activePlaylistId.set(updated.playlistId)
                    events += "plugin:${updated.playlistId}"
                }
            }
        }

        val configDir = Files.createTempDirectory("moemusic-runtime-reload-order")
        try {
            MoeMusicApi.registerPlugin(plugin)
            ServerRuntimeCoordinator.serverInit(
                channel = NoopNetworkChannel,
                configDir = configDir,
                pluginServicesFactory = { _, _, _ -> testPluginServices },
            )
            assertTrue(initialFetch.await(3, TimeUnit.SECONDS), "initial autoplay fetch should run")

            val configFile = PluginConfigIO.fileFor(configDir, plugin)
            PluginConfigIO.save(configFile, TestConfig(playlistId = "new-playlist"), TestConfig::class)
            events.clear()
            recordReloadFetch.set(true)

            ServerRuntimeCoordinator.reloadServerConfigFromDisk()

            assertTrue(reloadFetch.await(3, TimeUnit.SECONDS), "reload should refresh autoplay")
            assertEquals(
                listOf("plugin:new-playlist", "filter", "autoplay:new-playlist"),
                synchronized(events) { events.toList() },
            )
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    private val testPluginServices = ServerPluginServices(
        permissionService = AllowAllPermissionService,
        userActionService = UnusedUserActionService,
        mediaProbeService = NoopMediaProbeService,
    )

    private object NoopNetworkChannel : NetworkChannel {
        override fun sendToServer(packetId: PacketId, payload: ByteArray) = Unit
        override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) = Unit
        override fun sendToAllClients(packetId: PacketId, payload: ByteArray) = Unit
    }

    private object AllowAllPermissionService : IPermissionService {
        override fun has(permission: MoeMusicPermission, user: MoeMusicUser): Boolean = true
        override fun require(permission: MoeMusicPermission, user: MoeMusicUser?) = Unit
    }

    private object NoopMediaProbeService : IMediaProbeService {
        override suspend fun probeHttp(url: String, headers: Map<String, String>): UserResult<MediaProbeResult?> =
            UserResult.Success(null)
    }

    private object UnusedUserActionService : IUserActionService {
        override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): SearchResult = error("unused")

        override suspend fun submitBySourceAndId(
            sourceId: String,
            trackId: String,
            submitter: MoeMusicUser?,
            mode: TrackAddMode,
        ): SubmitOutcome = error("unused")

        override suspend fun submitBySelection(
            sourceId: String,
            selectionId: String,
            submitter: MoeMusicUser?,
            mode: TrackAddMode,
        ): SelectionSubmitOutcome = error("unused")

        override suspend fun submitResolved(
            track: TrackInfo,
            submitter: MoeMusicUser?,
            mode: TrackAddMode,
        ): SubmitOutcome = SubmitOutcome(track, TrackAddResult.QUEUED)

        override suspend fun submitIdentifier(
            identifier: String,
            submitter: MoeMusicUser?,
            mode: TrackAddMode,
        ): IdentifierSubmitOutcome = error("unused")

        override fun removeQueuedTrack(
            sourceId: String,
            trackId: String,
            requester: MoeMusicUser?,
        ): QueueRemoveOutcome = error("unused")

        override fun controlPlayback(
            action: PlaybackAction,
            requester: MoeMusicUser?,
            positionMs: Long,
        ): PlaybackActionOutcome = error("unused")
    }
}
