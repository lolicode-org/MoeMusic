package org.lolicode.moemusic.core.plugin

import kotlinx.serialization.Serializable
import org.lolicode.moemusic.api.*
import org.lolicode.moemusic.api.plugin.*
import org.lolicode.moemusic.api.service.*
import org.lolicode.moemusic.api.client.*
import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.plugin.ClientRuntimeContext
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

class PluginManagerTest {

    @BeforeTest
    fun resetStateBeforeTest() {
        resetPluginTestState()
    }

    @AfterTest
    fun resetStateAfterTest() {
        resetPluginTestState()
    }

    private val testPlaybackController = object : IPlaybackController {
        override val currentContext: TrackContext? = null
        override fun userQueueSnapshot(): List<TrackInfo> = emptyList()
        override fun play(track: TrackInfo, playback: PlaybackResource) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun seek(positionMs: Long) = Unit
        override fun skip() = Unit
        override fun stop() = Unit
        override fun enqueueAndPlay(track: TrackInfo) = Unit
        override fun removeQueuedTrack(
            sourceId: String,
            trackId: String,
            requester: MoeMusicUser?,
            bypassOwnership: Boolean,
        ): QueueRemoveResult = QueueRemoveResult.NOT_FOUND
    }

    private val testTrackSubmissionService = object : ITrackSubmissionService {
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

        override suspend fun submitResolvedFromSource(
            track: TrackInfo,
            submitter: MoeMusicUser?,
            mode: TrackAddMode,
        ): SubmitOutcome = SubmitOutcome(track, TrackAddResult.QUEUED)
    }

    private val testUserActionService = object : IUserActionService {
        override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): SearchResult = error("unused")
        override suspend fun submitBySourceAndId(sourceId: String, trackId: String, submitter: MoeMusicUser?, mode: TrackAddMode): SubmitOutcome = error("unused")
        override suspend fun submitBySelection(sourceId: String, selectionId: String, submitter: MoeMusicUser?, mode: TrackAddMode): SelectionSubmitOutcome = error("unused")
        override suspend fun submitResolved(track: TrackInfo, submitter: MoeMusicUser?, mode: TrackAddMode): SubmitOutcome = error("unused")
        override suspend fun submitIdentifier(identifier: String, submitter: MoeMusicUser?, mode: TrackAddMode): IdentifierSubmitOutcome = error("unused")
        override fun removeQueuedTrack(sourceId: String, trackId: String, requester: MoeMusicUser?): QueueRemoveOutcome = error("unused")
        override fun controlPlayback(action: PlaybackAction, requester: MoeMusicUser?, positionMs: Long): PlaybackActionOutcome = error("unused")
    }

    private val testMediaProbeService = object : IMediaProbeService {
        override suspend fun probeHttp(
            url: String,
            headers: Map<String, String>,
        ): UserResult<MediaProbeResult?> = UserResult.Success(null)
    }

    private val testClientPlaybackService = object : IClientPlaybackService {
        override val currentContext: TrackContext? = null
        override val searchCatalog: ClientSearchCatalog? = null
        override val currentParticipationState: UserParticipationState? = null
        override val currentAvailabilityIssue: ClientAvailabilityIssue? = null
        override val configuredVolumePercent: Int = 100
        override val effectiveVolumePercent: Int = 100
        override fun currentPositionMs(): Long? = null
        override fun setConfiguredVolumePercent(percent: Int) = Unit
        override fun setTransientVolumeOverride(ownerId: String, override: ClientVolumeOverride) = Unit
        override fun clearTransientVolumeOverride(ownerId: String) = Unit
        override fun isPlaybackEnabledForCurrentServer(): Boolean = true
        override fun setPlaybackEnabledForCurrentServer(enabled: Boolean) = Unit
        override fun syncParticipationWithCurrentConfig() = Unit
    }

    private val testClientRequestService = object : IClientRequestService {
        override suspend fun search(query: SearchQuery): ClientSearchPage = error("unused")
        override suspend fun requestQueue(): ClientQueueSnapshot = error("unused")
        override suspend fun removeQueuedTrack(sourceId: String, trackId: String): ClientActionFeedback = error("unused")
        override suspend fun submitTrack(track: TrackInfo, mode: TrackAddMode): ClientTrackSubmitResult = error("unused")
        override suspend fun submitIdentifier(identifier: String, mode: TrackAddMode): ClientIdentifierSubmitResult = error("unused")
        override suspend fun submitSelection(entry: SelectionEntry, mode: TrackAddMode): ClientSelectionSubmitResult = error("unused")
        override suspend fun controlPlayback(action: PlaybackAction, positionMs: Long): ClientActionFeedback = error("unused")
        override suspend fun updateContentFilter(
            target: ContentFilterMutationTarget,
            sourceId: String,
            valueId: String,
            note: String?,
            ban: Boolean,
        ): ClientContentFilterActionResult = error("unused")
    }

    private fun activateServerRuntimeForTests() {
        PluginManager.activateServerRuntime(
            playbackController = testPlaybackController,
            trackSubmissionService = testTrackSubmissionService,
            requestRateLimiter = RequestRateLimiter(),
            acquireAudienceLease = { object : PlaybackAudienceLease { override fun release() = Unit } },
            userActionService = testUserActionService,
            mediaProbeService = testMediaProbeService,
        )
    }

    private fun activateClientRuntimeForTests() {
        PluginManager.activateClientRuntime(testClientPlaybackService, testClientRequestService)
    }

    @Test
    fun `registerPlugin throws on duplicate plugin id`() {
        val pluginId = "test-duplicate-plugin-${System.nanoTime()}"
        val first = object : Plugin {
            override val id = pluginId
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
        }
        val second = object : Plugin {
            override val id = pluginId
            override val version = "2.0.0"
            override val supportedApiVersions = "*"
        }

        MoeMusicApi.registerPlugin(first)
        val error = assertFailsWith<DuplicateRegistrationException> {
            MoeMusicApi.registerPlugin(second)
        }

        assertContains(error.message.orEmpty(), pluginId)
        assertContains(error.message.orEmpty(), "refusing to register")
    }

    @Test
    fun `dispatchServerRuntimeLoad and server session load call their matching lifecycle callbacks`() {
        var runtimeLoadCalled = false
        var serverLoadCalled = false

        val plugin = object : Plugin {
            // Unique id per test run to avoid collision with global MoeMusicApi state
            override val id = "test-lifecycle-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
            override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) { runtimeLoadCalled = true }
            override fun onServerSessionLoad(ctx: ServerSessionContext) { serverLoadCalled = true }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)
        activateServerRuntimeForTests()
        PluginManager.dispatchServerRuntimeLoad()
        PluginManager.dispatchServerSessionLoad()

        assertTrue(runtimeLoadCalled, "onServerRuntimeLoad should have been called")
        assertTrue(serverLoadCalled, "onServerSessionLoad should have been called")
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `initialize discovers standalone plugin jar providers`() {
        val pluginId = "test-standalone-${System.nanoTime()}"
        val runtimeProperty = "moemusic.test.runtime.$pluginId"
        val tmpDir = Files.createTempDirectory("moemusic-test")
        createStandalonePluginJar(
            rootConfigDir = tmpDir,
            fileName = "standalone.jar",
            providerClassName = "org.lolicode.moemusic.testplugin.StandaloneProvider",
            pluginId = pluginId,
            runtimeProperty = runtimeProperty,
        )

        try {
            PluginManager.initialize(tmpDir)
            activateServerRuntimeForTests()
            PluginManager.dispatchServerRuntimeLoad()

            assertEquals(pluginId, PluginManager.plugins.single().id)
            assertEquals("runtime", System.getProperty(runtimeProperty))
        } finally {
            System.clearProperty(runtimeProperty)
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `explicit and standalone duplicate plugin ids fail initialization`() {
        val pluginId = "test-duplicate-standalone-${System.nanoTime()}"
        val explicitPlugin = object : Plugin {
            override val id = pluginId
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(explicitPlugin)
        createStandalonePluginJar(
            rootConfigDir = tmpDir,
            fileName = "duplicate.jar",
            providerClassName = "org.lolicode.moemusic.testplugin.DuplicateProvider",
            pluginId = pluginId,
        )

        val error = assertFailsWith<DuplicateRegistrationException> {
            PluginManager.initialize(tmpDir)
        }

        assertContains(error.message.orEmpty(), pluginId)
        assertContains(error.message.orEmpty(), "explicit MoeMusicApi registration")
        assertContains(error.message.orEmpty(), "duplicate.jar")
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `standalone jar without provider descriptor is skipped`() {
        val tmpDir = Files.createTempDirectory("moemusic-test")
        createServiceOnlyJar(tmpDir, "empty.jar", providerClassName = null)

        PluginManager.initialize(tmpDir)

        assertTrue(PluginManager.plugins.isEmpty())
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `standalone jar with missing provider class fails initialization`() {
        val tmpDir = Files.createTempDirectory("moemusic-test")
        createServiceOnlyJar(tmpDir, "broken.jar", providerClassName = "missing.Provider")

        val error = assertFailsWith<IllegalStateException> {
            PluginManager.initialize(tmpDir)
        }

        assertContains(error.message.orEmpty(), "broken.jar")
        assertContains(error.message.orEmpty(), "Provider class 'missing.Provider' was not found")
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `server session cycles do not duplicate runtime event subscriptions`() {
        var received = 0
        var serverLoadCount = 0
        data class RuntimeEvent(val label: String)

        val plugin = object : Plugin {
            override val id = "test-event-reset-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"

            override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
                ctx.eventBus.subscribe(RuntimeEvent::class.java) { received++ }
            }

            override fun onServerSessionLoad(ctx: ServerSessionContext) {
                serverLoadCount++
            }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)
        activateServerRuntimeForTests()
        PluginManager.dispatchServerRuntimeLoad()
        PluginManager.dispatchServerSessionLoad()
        PluginManager.dispatchServerSessionUnload()

        PluginManager.dispatchServerSessionLoad()
        PluginManager.eventBus.fire(RuntimeEvent("cycle-2"))

        assertEquals(2, serverLoadCount, "server session lifecycle should run once per session")
        assertEquals(1, received, "runtime subscription should remain single across session restarts")
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plugin with incompatible API version range fails initialization`() {
        val plugin = object : Plugin {
            override val id = "test-compat-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = ">=999.0.0"
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        val error = assertFailsWith<IllegalStateException> {
            PluginManager.initialize(tmpDir)
        }

        assertContains(error.message.orEmpty(), plugin.id)
        assertContains(error.message.orEmpty(), "requires API version")
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plugin built against API 1_x range is refused after 2_0 bump`() {
        // The plugin compatibility version is now 2.0.0, so a plugin declaring the historical
        // 1.x range must be cleanly refused at load (fail-fast) rather than crash at runtime.
        val plugin = object : Plugin {
            override val id = "test-compat-1x-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = ">=1.0.0 <2.0.0"
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        val error = assertFailsWith<IllegalStateException> {
            PluginManager.initialize(tmpDir)
        }

        assertContains(error.message.orEmpty(), plugin.id)
        assertContains(error.message.orEmpty(), "requires API version")
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plugin with invalid config id fails initialization`() {
        val plugin = object : Plugin {
            override val id = "test-invalid-config-${System.nanoTime()}"
            override val configId = "bad:id"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        val error = assertFailsWith<IllegalStateException> {
            PluginManager.initialize(tmpDir)
        }

        assertContains(error.message.orEmpty(), plugin.id)
        assertContains(error.message.orEmpty(), "invalid configId")
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `filesystem lang override is loaded`() {
        val plugin = object : Plugin {
            override val id = "test-override-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        val overrideDir = tmpDir.resolve("lang").resolve(plugin.id)
        Files.createDirectories(overrideDir)
        Files.writeString(
            overrideDir.resolve("en_us.json"),
            $$"{\"test.override.message\":\"Override %1$s\"}",
        )
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)

        assertEquals(
            "Override value",
            Localization.render("en_us", LocalizedText.key("test.override.message", "value")),
        )
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `initialize creates missing plugin config file from defaults`() {
        @Serializable
        data class TestConfig(val enabled: Boolean = true)

        val plugin = object : Plugin {
            override val id = "test-config-default-file-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
            override val configSpec = pluginConfigSpec(::TestConfig) {
                boolean(
                    "enabled",
                    { it.enabled },
                    updater = { config, value -> config.copy(enabled = value) },
                )
            }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)

        val configFile = requireNotNull(PluginManager.pluginConfigFile(plugin))
        assertTrue(Files.exists(configFile), "Plugin config file should be created during initialization")
        assertEquals(tmpDir.resolve("plugin-configs"), configFile.parent)
        assertEquals(TestConfig(), PluginConfigIO.loadAnyStrict(configFile, plugin.configSpec))
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `initialize keeps existing plugin config file unchanged`() {
        @Serializable
        data class TestConfig(val enabled: Boolean = true)

        val plugin = object : Plugin {
            override val id = "test-config-existing-file-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
            override val configSpec = pluginConfigSpec(::TestConfig) {
                boolean(
                    "enabled",
                    { it.enabled },
                    updater = { config, value -> config.copy(enabled = value) },
                )
            }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        val configFile = PluginConfigIO.fileFor(tmpDir, plugin)
        PluginConfigIO.save(configFile, TestConfig(enabled = false), TestConfig::class)
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)

        assertEquals(TestConfig(enabled = false), PluginConfigIO.loadAnyStrict(configFile, plugin.configSpec))
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `runtime context exposes plugin data directory under plugin-data`() {
        var pluginContext: ServerRuntimeContext? = null

        val plugin = object : Plugin {
            override val id = "test-plugin-data-dir-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"

            override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
                pluginContext = ctx
            }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)
        activateServerRuntimeForTests()
        PluginManager.dispatchServerRuntimeLoad()

        val ctx = assertNotNull(pluginContext)
        assertEquals(tmpDir.resolve("plugin-data").resolve(plugin.configId), ctx.pluginDataDir)
        assertTrue(Files.isDirectory(ctx.pluginDataDir))
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `saveConfig notifies onConfigChanged listeners`() {
        @Serializable
        data class TestConfig(val enabled: Boolean = true)

        var pluginContext: ServerRuntimeContext? = null
        var observedConfig: TestConfig? = null

        val plugin = object : Plugin {
            override val id = "test-config-events-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
            override val configSpec = pluginConfigSpec(::TestConfig) {
                boolean(
                    "enabled",
                    { it.enabled },
                    updater = { config, value -> config.copy(enabled = value) },
                )
            }

            override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
                pluginContext = ctx
                ctx.onConfigChanged(configSpec) { observedConfig = it }
            }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)
        activateServerRuntimeForTests()
        PluginManager.dispatchServerRuntimeLoad()

        val ctx = assertNotNull(pluginContext, "onServerRuntimeLoad should capture ServerRuntimeContext")
        val updated = TestConfig(enabled = false)
        ctx.saveConfig(plugin.configSpec, updated)

        assertEquals(updated, observedConfig, "onConfigChanged should receive the saved config")
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `reloadConfigFilesFromDisk notifies live plugin listeners`() {
        @Serializable
        data class TestConfig(val enabled: Boolean = true)

        var observedConfig: TestConfig? = null

        val plugin = object : Plugin {
            override val id = "test-config-disk-reload-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"
            override val configSpec = pluginConfigSpec(::TestConfig) {
                boolean(
                    "enabled",
                    { it.enabled },
                    updater = { config, value -> config.copy(enabled = value) },
                )
            }

            override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
                ctx.onConfigChanged(configSpec) { observedConfig = it }
            }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)
        activateServerRuntimeForTests()
        PluginManager.dispatchServerRuntimeLoad()

        val configFile = requireNotNull(PluginManager.pluginConfigFile(plugin))
        PluginConfigIO.save(configFile, TestConfig(enabled = false), TestConfig::class)
        observedConfig = null

        val report = PluginManager.reloadConfigFilesFromDisk()

        assertEquals(TestConfig(enabled = false), observedConfig)
        assertTrue(plugin.id in report.processedPluginIds)
        assertTrue(plugin.id in report.notifiedPluginIds)
        assertTrue(report.failures.isEmpty())
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `client runtime context exposes client services`() {
        var pluginContext: ClientRuntimeContext? = null

        val plugin = object : Plugin {
            override val id = "test-client-source-registration-${System.nanoTime()}"
            override val version = "1.0.0"
            override val supportedApiVersions = "*"

            override fun onClientRuntimeLoad(ctx: ClientRuntimeContext) {
                pluginContext = ctx
            }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)
        PluginManager.initialize(tmpDir)
        activateClientRuntimeForTests()
        PluginManager.dispatchClientRuntimeLoad()

        val ctx = assertNotNull(pluginContext)
        assertSame(testClientPlaybackService, ctx.clientPlaybackService)
        assertSame(testClientRequestService, ctx.clientRequestService)
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `dispatchServerRuntimeLoad fails fast on duplicate music source id`() {
        val pluginId = "test-duplicate-source-${System.nanoTime()}"
        var attemptedRegistration = false

        val sourceA = object : MusicSource {
            override val id = "duplicate-source-${System.nanoTime()}"
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution {
                error("unused")
            }
        }
        val sourceB = object : MusicSource {
            override val id = sourceA.id
            override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution {
                error("unused")
            }
        }

        val plugin = object : Plugin {
            override val id = pluginId
            override val version = "1.0.0"
            override val supportedApiVersions = "*"

            override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
                if (attemptedRegistration) return
                attemptedRegistration = true
                ctx.registerMusicSource(sourceA)
                ctx.registerMusicSource(sourceB)
            }
        }

        val tmpDir = Files.createTempDirectory("moemusic-test")
        MoeMusicApi.registerPlugin(plugin)

        PluginManager.initialize(tmpDir)
        activateServerRuntimeForTests()
        val error = assertFailsWith<DuplicateRegistrationException> {
            PluginManager.dispatchServerRuntimeLoad()
        }

        assertContains(error.message.orEmpty(), sourceA.id)
        assertContains(error.message.orEmpty(), pluginId)
        tmpDir.toFile().deleteRecursively()
    }

    private fun createStandalonePluginJar(
        rootConfigDir: Path,
        fileName: String,
        providerClassName: String,
        pluginId: String,
        runtimeProperty: String? = null,
    ): Path {
        val workDir = Files.createTempDirectory("moemusic-plugin-compile")
        val classesDir = workDir.resolve("classes")
        val sourceFile = workDir.resolve("src")
            .resolve(providerClassName.replace('.', File.separatorChar) + ".java")
        Files.createDirectories(sourceFile.parent)
        Files.createDirectories(classesDir)

        val packageName = providerClassName.substringBeforeLast('.')
        val simpleName = providerClassName.substringAfterLast('.')
        val runtimeCallback = runtimeProperty?.let { property ->
            """
            @Override
            public void onServerRuntimeLoad(ServerRuntimeContext ctx) {
                System.setProperty("$property", "runtime");
            }
            """.trimIndent()
        }.orEmpty()

        Files.writeString(
            sourceFile,
            """
            package $packageName;

            import java.util.List;
            import org.lolicode.moemusic.api.plugin.Plugin;
            import org.lolicode.moemusic.api.plugin.PluginProvider;
            import org.lolicode.moemusic.api.plugin.ServerRuntimeContext;

            public class $simpleName implements PluginProvider {
                @Override
                public Iterable<Plugin> plugins() {
                    return List.of(new LoadedPlugin());
                }

                public static class LoadedPlugin implements Plugin {
                    @Override
                    public String getId() {
                        return "$pluginId";
                    }

                    @Override
                    public String getVersion() {
                        return "1.0.0";
                    }

                    @Override
                    public String getSupportedApiVersions() {
                        return "*";
                    }

                    $runtimeCallback
                }
            }
            """.trimIndent(),
        )

        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required to build the standalone plugin test jar.")
        val result = compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classesDir.toString(),
            sourceFile.toString(),
        )
        assertEquals(0, result, "Standalone plugin test source should compile")

        val jar = rootConfigDir.resolve("plugins").resolve(fileName)
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar)).use { jarOut ->
            Files.walk(classesDir).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .forEach { classFile ->
                        val entryName = classesDir.relativize(classFile).toString().replace(File.separatorChar, '/')
                        jarOut.putNextEntry(JarEntry(entryName))
                        Files.copy(classFile, jarOut)
                        jarOut.closeEntry()
                    }
            }
            jarOut.putNextEntry(JarEntry("META-INF/services/org.lolicode.moemusic.api.plugin.PluginProvider"))
            jarOut.write(providerClassName.toByteArray(Charsets.UTF_8))
            jarOut.closeEntry()
        }

        workDir.toFile().deleteRecursively()
        return jar
    }

    private fun createServiceOnlyJar(
        rootConfigDir: Path,
        fileName: String,
        providerClassName: String?,
    ): Path {
        val jar = rootConfigDir.resolve("plugins").resolve(fileName)
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar)).use { jarOut ->
            jarOut.putNextEntry(JarEntry("placeholder.txt"))
            jarOut.write("placeholder".toByteArray(Charsets.UTF_8))
            jarOut.closeEntry()
            if (providerClassName != null) {
                jarOut.putNextEntry(JarEntry("META-INF/services/org.lolicode.moemusic.api.plugin.PluginProvider"))
                jarOut.write(providerClassName.toByteArray(Charsets.UTF_8))
                jarOut.closeEntry()
            }
        }
        return jar
    }
}
