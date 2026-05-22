package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.*
import org.lolicode.moemusic.api.plugin.*
import org.lolicode.moemusic.api.service.*
import org.lolicode.moemusic.api.client.IClientPlaybackService
import org.lolicode.moemusic.api.client.IClientRequestService
import org.lolicode.moemusic.api.service.IMediaProbeService
import org.lolicode.moemusic.api.service.IUserActionService
import org.lolicode.moemusic.core.event.EventBusImpl
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.i18n.PluginI18nRegistry
import org.lolicode.moemusic.core.permission.PermissionServiceImpl
import org.lolicode.moemusic.core.plugin.PluginManager.activateServerRuntime
import org.lolicode.moemusic.core.plugin.PluginManager.dispatchClientRuntimeLoad
import org.lolicode.moemusic.core.plugin.PluginManager.dispatchClientRuntimeUnload
import org.lolicode.moemusic.core.plugin.PluginManager.dispatchServerRuntimeLoad
import org.lolicode.moemusic.core.plugin.PluginManager.dispatchServerRuntimeUnload
import org.lolicode.moemusic.core.plugin.PluginManager.dispatchServerSessionLoad
import org.lolicode.moemusic.core.plugin.PluginManager.dispatchServerSessionUnload
import org.lolicode.moemusic.core.plugin.PluginManager.initialize
import org.lolicode.moemusic.core.plugin.PluginManager.musicSources
import org.lolicode.moemusic.core.plugin.PluginManager.playbackController
import org.lolicode.moemusic.core.plugin.PluginManager.registerMusicSource
import org.lolicode.moemusic.core.plugin.PluginManager.reset
import org.lolicode.moemusic.core.source.IdentifierResolutionService
import org.lolicode.moemusic.core.source.SearchServiceImpl
import org.lolicode.moemusic.core.ratelimit.RateLimitServiceImpl
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.JarURLConnection
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Orchestrates plugin lifecycle and owns the shared event bus.
 *
 * Call order (from the platform bootstrap):
 * 1. [initialize] — once per JVM side after all mod initializers
 * 2. [activateServerRuntime] — once per logical server runtime
 * 3. [registerMusicSource] and [dispatchServerRuntimeLoad]
 * 4. [dispatchServerSessionLoad] / [dispatchServerSessionUnload] per Minecraft server session
 * 5. [dispatchClientRuntimeLoad] / [dispatchClientRuntimeUnload] per client runtime
 * 6. [dispatchServerRuntimeUnload] followed by [reset] when the logical server runtime truly ends
 */
object PluginManager {

    private val logger = LoggerFactory.getLogger(PluginManager::class.java)
    private val builtinPlugins: List<Plugin> = emptyList()
    private const val BUILTIN_NAMESPACE: String = "moemusic"
    private const val STANDALONE_PLUGIN_DIR: String = "plugins"

    /** Valid pattern for [Plugin.configId]. Must match this to be a safe filename on all OSes. */
    private val CONFIG_ID_RE = Regex("^[a-z0-9_-]+$")

    val eventBus = EventBusImpl()
    private val pluginI18n: I18nRegistry = PluginI18nRegistry

    /** All sources registered by plugins or builtins. */
    val musicSources: MutableList<MusicSource> = mutableListOf()

    /**
     * Search routing service. Lazily references [musicSources] so newly registered
     * sources are automatically included without re-creating the instance.
     */
    val searchService: SearchServiceImpl = SearchServiceImpl(musicSources)
    val identifierResolutionService: IdentifierResolutionService = IdentifierResolutionService(musicSources)

    /** Server-side playback controller for the persistent logical server runtime. */
    var playbackController: IPlaybackController? = null
        private set

    /**
     * Track submission service. Set by the platform bootstrap alongside [playbackController];
     * null on the client side or before [activateServerRuntime].
     * Exposed to plugins via [org.lolicode.moemusic.api.plugin.ServerRuntimeContext.trackSubmissionService].
     */
    var trackSubmissionService: ITrackSubmissionService? = null
        private set

    private var requestRateLimiter: RequestRateLimiter? = null
    private var audienceLeaseFactory: ((String) -> PlaybackAudienceLease)? = null
    private var permissionService: IPermissionService? = null
    private var userActionService: IUserActionService? = null
    private var mediaProbeService: IMediaProbeService? = null
    private var clientPlaybackService: IClientPlaybackService? = null
    private var clientRequestService: IClientRequestService? = null

    private var configDir: Path? = null
    private var initialized: Boolean = false
    private var clientRuntimeActive: Boolean = false
    private var serverRuntimeActive: Boolean = false

    /** Validated, initialized plugins keyed by id. */
    private val registeredPlugins = LinkedHashMap<String, Plugin>()

    /** Per-plugin client/runtime contexts, keyed by plugin id. */
    private val clientContexts = LinkedHashMap<String, ClientRuntimeContextImpl>()
    private val serverRuntimeContexts = LinkedHashMap<String, ServerRuntimeContextImpl>()
    private val serverSessionContexts = LinkedHashMap<String, ServerSessionContextImpl>()
    private val configChangeListeners = LinkedHashMap<String, MutableList<(Any) -> Unit>>()
    private val musicSourceRegistrations = LinkedHashMap<String, MusicSourceRegistration>()
    private val pluginClassLoaders = mutableListOf<Closeable>()

    /** Validated, initialized plugins in display order. */
    val plugins: List<Plugin>
        get() = registeredPlugins.values.toList()

    /** Snapshot the currently registered music sources for safe iteration across async work. */
    fun musicSourceSnapshot(): List<MusicSource> = ArrayList(musicSources)

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Build persistent plugin metadata and lang bundles.
     *
     * Idempotent inside one JVM runtime. Repeated calls after successful initialization are
     * ignored, which allows both client and server bootstrap hooks to safely call it before the
     * first logical server session exists.
     *
     * Must be called **after** all mod initializers have run (i.e. from a server/client
     * lifecycle hook, not the early mod initializer body) so loader-mod plugins have had time
     * to call [MoeMusicApi.registerPlugin]. Standalone plugin jars are discovered from the
     * `<configDir>/plugins/` directory during this call.
     *
     * @param configDir Root config directory.
     */
    fun initialize(configDir: Path) {
        if (initialized) return
        this.configDir = configDir
        loadBundledLangResources()
        val loadedPluginJars = PluginJarDiscovery.discover(configDir.resolve(STANDALONE_PLUGIN_DIR))

        try {
            for (candidate in discoveredPlugins(loadedPluginJars.plugins)) {
                val plugin = candidate.plugin
                requireCompatibleApiVersion(plugin)
                requireValidConfigId(plugin)
                loadLangResources(plugin)
                registeredPlugins[plugin.id] = plugin
                plugin.configSpec?.let { spec ->
                    PluginConfigIO.ensureExists(PluginConfigIO.fileFor(configDir, plugin), spec)
                }
                logger.info("Registered plugin: {} v{} ({})", plugin.id, plugin.version, candidate.origin)
            }
            pluginClassLoaders += loadedPluginJars.classLoaders
        } catch (e: Exception) {
            loadedPluginJars.close()
            throw e
        }
        initialized = true
    }

    /** Attach client-runtime services and build per-plugin client contexts. */
    fun activateClientRuntime(
        clientPlaybackService: IClientPlaybackService,
        clientRequestService: IClientRequestService,
    ) {
        require(initialized) { "PluginManager.initialize() must run before activateClientRuntime()." }
        if (clientRuntimeActive) return

        this.clientPlaybackService = clientPlaybackService
        this.clientRequestService = clientRequestService
        clientContexts.clear()
        for (plugin in plugins) {
            clientContexts[plugin.id] = buildClientContext(plugin)
        }
        clientRuntimeActive = true
    }

    /**
     * Attach persistent logical-server services and build server-runtime / server-session
     * contexts the first time a logical server runtime becomes available in this JVM.
     */
    fun activateServerRuntime(
        playbackController: IPlaybackController,
        trackSubmissionService: ITrackSubmissionService,
        requestRateLimiter: RequestRateLimiter,
        acquireAudienceLease: (String) -> PlaybackAudienceLease,
        permissionService: IPermissionService = PermissionServiceImpl(),
        userActionService: IUserActionService,
        mediaProbeService: IMediaProbeService,
    ) {
        require(initialized) { "PluginManager.initialize() must run before activateServerRuntime()." }
        if (serverRuntimeActive) return

        this.playbackController = playbackController
        this.trackSubmissionService = trackSubmissionService
        this.requestRateLimiter = requestRateLimiter
        this.audienceLeaseFactory = acquireAudienceLease
        this.permissionService = permissionService
        this.userActionService = userActionService
        this.mediaProbeService = mediaProbeService
        serverRuntimeContexts.clear()
        serverSessionContexts.clear()
        for (plugin in plugins) {
            serverRuntimeContexts[plugin.id] = buildServerRuntimeContext(plugin)
            serverSessionContexts[plugin.id] = buildServerSessionContext(plugin)
        }
        serverRuntimeActive = true
    }

    /**
     * Clear all accumulated plugin state.
     *
     * This is a full runtime teardown used for final JVM shutdown paths and tests. It is
     * intentionally **not** part of ordinary integrated-server session restarts.
     */
    fun reset() {
        initialized = false
        clientRuntimeActive = false
        serverRuntimeActive = false
        registeredPlugins.clear()
        clientContexts.clear()
        serverRuntimeContexts.clear()
        serverSessionContexts.clear()
        configChangeListeners.clear()
        musicSourceRegistrations.clear()
        musicSources.clear()
        eventBus.clear()
        pluginClassLoaders.forEach { closeQuietly(it) }
        pluginClassLoaders.clear()
        playbackController = null
        trackSubmissionService = null
        requestRateLimiter = null
        audienceLeaseFactory = null
        permissionService = null
        userActionService = null
        mediaProbeService = null
        clientPlaybackService = null
        clientRequestService = null
        configDir = null
        Localization.clear()
    }

    // -------------------------------------------------------------------------
    // Music source registration (builtins call this before dispatchServerRuntimeLoad)
    // -------------------------------------------------------------------------

    fun registerMusicSource(source: MusicSource) {
        check(serverRuntimeActive) {
            "MusicSource registration requires an active logical server runtime."
        }
        registerMusicSource(source, "builtin runtime")
    }

    // -------------------------------------------------------------------------
    // Lifecycle dispatch
    // -------------------------------------------------------------------------

    fun dispatchServerRuntimeLoad() = serverRuntimeContexts.forEach { (id, ctx) ->
        runSafe(id, "onServerRuntimeLoad") { pluginById(id)?.onServerRuntimeLoad(ctx) }
    }

    fun dispatchServerSessionLoad() = serverSessionContexts.forEach { (id, ctx) ->
        runSafe(id, "onServerSessionLoad") { pluginById(id)?.onServerSessionLoad(ctx) }
    }

    fun dispatchClientRuntimeLoad() = clientContexts.forEach { (id, ctx) ->
        runSafe(id, "onClientRuntimeLoad") { pluginById(id)?.onClientRuntimeLoad(ctx) }
    }

    fun dispatchServerSessionUnload() = serverSessionContexts.keys.forEach { id ->
        runSafe(id, "onServerSessionUnload") { pluginById(id)?.onServerSessionUnload() }
    }

    fun dispatchServerRuntimeUnload() = serverRuntimeContexts.keys.forEach { id ->
        runSafe(id, "onServerRuntimeUnload") { pluginById(id)?.onServerRuntimeUnload() }
    }

    fun dispatchClientRuntimeUnload() = clientContexts.keys.forEach { id ->
        runSafe(id, "onClientRuntimeUnload") { pluginById(id)?.onClientRuntimeUnload() }
    }

    /** Resolve the config file path for [plugin], or null if [initialize] has not run yet. */
    fun pluginConfigFile(plugin: Plugin): Path? =
        configDir?.let { PluginConfigIO.fileFor(it, plugin) }

    /** Register a config change listener for [pluginId]. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> registerConfigChangeListener(pluginId: String, listener: (T) -> Unit) {
        configChangeListeners
            .getOrPut(pluginId) { mutableListOf() }
            .add { value -> listener(value as T) }
    }

    /** Notify listeners that [pluginId]'s config changed. Returns the listener count. */
    fun notifyConfigChanged(pluginId: String, value: Any): Int {
        val listeners = configChangeListeners[pluginId].orEmpty()
        listeners.forEach { listener ->
            runSafe(pluginId, "onConfigChanged") { listener(value) }
        }
        return listeners.size
    }

    /**
     * Reload all plugin config files that expose [Plugin.configSpec] from disk and notify live
     * runtime listeners.
     */
    fun reloadConfigFilesFromDisk(): PluginConfigReloadReport {
        val processedPluginIds = mutableListOf<String>()
        val notifiedPluginIds = mutableListOf<String>()
        val failures = linkedMapOf<String, String>()

        for (plugin in plugins) {
            val spec = plugin.configSpec ?: continue
            val file = pluginConfigFile(plugin) ?: continue
            processedPluginIds += plugin.id
            try {
                val value = PluginConfigIO.loadAnyStrict(file, spec)
                if (notifyConfigChanged(plugin.id, value) > 0) {
                    notifiedPluginIds += plugin.id
                }
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                failures[plugin.id] = message
                logger.warn("Failed to reload plugin config for '{}': {}", plugin.id, message)
            }
        }

        return PluginConfigReloadReport(
            processedPluginIds = processedPluginIds,
            notifiedPluginIds = notifiedPluginIds,
            failures = failures,
        )
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    internal fun registerPluginMusicSource(pluginId: String, source: MusicSource) {
        check(serverRuntimeActive) {
            "MusicSource registration requires an active logical server runtime."
        }
        registerMusicSource(source, "plugin '$pluginId'")
    }

    private fun pluginById(id: String): Plugin? =
        registeredPlugins[id]

    private fun buildClientContext(plugin: Plugin): ClientRuntimeContextImpl {
        val rootConfigDir = requireNotNull(configDir) { "PluginManager.initialize() has not run yet." }
        return ClientRuntimeContextImpl(
            plugin.id,
            PluginConfigIO.fileFor(rootConfigDir, plugin),
            PluginConfigIO.dataDirFor(rootConfigDir, plugin),
            pluginI18n,
            eventBus,
            contentFilterService = ContentFilterRuntime,
            clientPlaybackService = requireNotNull(clientPlaybackService),
            clientRequestService = requireNotNull(clientRequestService),
        )
    }

    private fun buildServerRuntimeContext(plugin: Plugin): ServerRuntimeContextImpl {
        val rootConfigDir = requireNotNull(configDir) { "PluginManager.initialize() has not run yet." }
        return ServerRuntimeContextImpl(
            plugin.id,
            PluginConfigIO.fileFor(rootConfigDir, plugin),
            PluginConfigIO.dataDirFor(rootConfigDir, plugin),
            pluginI18n,
            eventBus,
            playbackController = requireNotNull(playbackController),
            searchService = searchService,
            identifierResolutionService = identifierResolutionService,
            trackSubmissionService = requireNotNull(trackSubmissionService),
            userActionService = requireNotNull(userActionService),
            contentFilterService = ContentFilterRuntime,
            rateLimitService = requireNotNull(requestRateLimiter).let(::RateLimitServiceImpl),
            permissionService = requireNotNull(permissionService),
            mediaProbeService = requireNotNull(mediaProbeService),
        )
    }

    private fun buildServerSessionContext(plugin: Plugin): ServerSessionContextImpl {
        val rootConfigDir = requireNotNull(configDir) { "PluginManager.initialize() has not run yet." }
        return ServerSessionContextImpl(
            plugin.id,
            PluginConfigIO.fileFor(rootConfigDir, plugin),
            PluginConfigIO.dataDirFor(rootConfigDir, plugin),
            pluginI18n,
            playbackController = requireNotNull(playbackController),
            searchService = searchService,
            identifierResolutionService = identifierResolutionService,
            trackSubmissionService = requireNotNull(trackSubmissionService),
            userActionService = requireNotNull(userActionService),
            contentFilterService = ContentFilterRuntime,
            rateLimitService = requireNotNull(requestRateLimiter).let(::RateLimitServiceImpl),
            permissionService = requireNotNull(permissionService),
            mediaProbeService = requireNotNull(mediaProbeService),
            acquirePlaybackAudienceLeaseImpl = requireNotNull(audienceLeaseFactory),
        )
    }

    private fun runSafe(pluginId: String, phase: String, block: () -> Unit) {
        try {
            block()
        } catch (e: DuplicateRegistrationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Plugin '{}' threw during {}: {}", pluginId, phase, e.message, e)
        }
    }

    private fun discoveredPlugins(standalonePlugins: List<PluginJarDiscovery.DiscoveredPlugin>): List<PluginCandidate> {
        val candidates =
            builtinPlugins.map { PluginCandidate(it, "builtin plugin") } +
                    MoeMusicApi.plugins.map { PluginCandidate(it, "explicit MoeMusicApi registration") } +
                    standalonePlugins.map { PluginCandidate(it.plugin, it.origin) }

        val discovered = LinkedHashMap<String, PluginCandidate>()
        for (candidate in candidates) {
            val plugin = candidate.plugin
            val existing = discovered.putIfAbsent(plugin.id, candidate)
            if (existing != null) {
                throw DuplicateRegistrationException(
                    "Duplicate MoeMusic plugin id '${plugin.id}': ${describePlugin(existing.plugin)} from ${existing.origin} " +
                            "is already scheduled for load; refusing to load ${describePlugin(plugin)} from ${candidate.origin}. " +
                            "Plugin ids must be globally unique.",
                )
            }
        }
        return discovered.values.toList()
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (e: Exception) {
            logger.debug("Failed to close plugin classloader: {}", e.message)
        }
    }

    private fun registerMusicSource(source: MusicSource, owner: String) {
        val existing = musicSourceRegistrations[source.id]
        if (existing != null) {
            throw DuplicateRegistrationException(
                "Duplicate MoeMusic music source id '${source.id}': ${describeSource(existing.source)} is already registered by ${existing.owner}; " +
                        "refusing to register ${describeSource(source)} by $owner. Music source ids must be globally unique.",
            )
        }
        musicSourceRegistrations[source.id] = MusicSourceRegistration(source, owner)
        musicSources.add(source)
        logger.info("Registered MusicSource: {} ({})", source.id, owner)
    }

    private fun describePlugin(plugin: Plugin): String =
        "${plugin.javaClass.name} v${plugin.version}"

    private fun describeSource(source: MusicSource): String =
        source.javaClass.name

    private data class MusicSourceRegistration(
        val source: MusicSource,
        val owner: String,
    )

    private data class PluginCandidate(
        val plugin: Plugin,
        val origin: String,
    )

    data class PluginConfigReloadReport(
        val processedPluginIds: List<String>,
        val notifiedPluginIds: List<String>,
        val failures: Map<String, String>,
    )

    private fun loadBundledLangResources() {
        val classLoader = PluginManager::class.java.classLoader
        val codeSource = PluginManager::class.java.protectionDomain?.codeSource?.location?.let { URI(it.toString()) }
        loadLangResources(
            ownerLabel = "builtin namespace '$BUILTIN_NAMESPACE'",
            namespace = BUILTIN_NAMESPACE,
            classLoader = classLoader,
            codeSource = codeSource,
        )
    }

    private fun loadLangResources(plugin: Plugin) {
        loadLangResources(
            ownerLabel = "plugin '${plugin.id}'",
            namespace = plugin.id.substringBefore(':'),
            classLoader = plugin.javaClass.classLoader,
            codeSource = plugin.javaClass.protectionDomain?.codeSource?.location?.let { URI(it.toString()) },
        )
    }

    private fun loadLangResources(
        ownerLabel: String,
        namespace: String,
        classLoader: ClassLoader,
        codeSource: URI?,
    ) {
        val resourcePrefix = "assets/$namespace/lang/"
        val loadedAny = linkedSetOf<String>()

        classLoader.getResources(resourcePrefix).toList().forEach { url ->
            when (url.protocol) {
                "file", "union" -> loadedAny += loadLangDirectory(ownerLabel, URI(url.toString()))
                "jar" -> loadedAny += loadLangJar(ownerLabel, url.openConnection() as JarURLConnection, resourcePrefix)
            }
        }

        if (loadedAny.isEmpty() && codeSource != null) {
            loadedAny += loadLangCodeSource(ownerLabel, codeSource, resourcePrefix)
        }

        loadedAny += loadLangOverrides(ownerLabel, namespace)

        if (loadedAny.isEmpty()) {
            logger.debug("No lang resources found for {} under {}", ownerLabel, resourcePrefix)
        } else {
            logger.info("Loaded {} lang bundle(s) for {}: {}", loadedAny.size, ownerLabel, loadedAny.joinToString())
        }
    }

    private fun loadLangDirectory(ownerLabel: String, directoryUri: URI): Set<String> {
        val directory = try {
            Path.of(directoryUri)
        } catch (_: Exception) {
            return emptySet()
        }
        return loadLangFiles(ownerLabel, directory)
    }

    private fun loadLangJar(ownerLabel: String, connection: JarURLConnection, resourcePrefix: String): Set<String> =
        connection.jarFile.use { jar -> loadLangJarEntries(ownerLabel, jar, resourcePrefix) }

    private fun loadLangJarEntries(ownerLabel: String, jar: JarFile, resourcePrefix: String): Set<String> {
        val loaded = linkedSetOf<String>()
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.startsWith(resourcePrefix) || !entry.name.endsWith(".json")) continue
            jar.getInputStream(entry).use { input ->
                val locale = entry.name.removePrefix(resourcePrefix).removeSuffix(".json")
                registerLangBundle(ownerLabel, locale, input.readBytes().toString(Charsets.UTF_8))
                loaded += locale
            }
        }
        return loaded
    }

    private fun loadLangCodeSource(ownerLabel: String, codeSourceUri: URI, resourcePrefix: String): Set<String> =
        when (codeSourceUri.scheme) {
            "file" -> {
                val path = try {
                    Path.of(codeSourceUri)
                } catch (_: Exception) {
                    return emptySet()
                }
                when {
                    Files.isDirectory(path) -> loadLangDirectory(ownerLabel, path.resolve(resourcePrefix).toUri())
                    path.fileName.toString().endsWith(".jar") -> JarFile(path.toFile()).use { jar ->
                        loadLangJarEntries(ownerLabel, jar, resourcePrefix)
                    }

                    else -> emptySet()
                }
            }

            "jar" -> {
                val fileSystem = try {
                    FileSystems.getFileSystem(codeSourceUri)
                } catch (_: FileSystemNotFoundException) {
                    FileSystems.newFileSystem(codeSourceUri, emptyMap<String, Any>())
                }
                loadLangDirectory(ownerLabel, fileSystem.getPath(resourcePrefix).toUri())
            }

            "union" -> loadLangDirectory(ownerLabel, Path.of(codeSourceUri).resolve(resourcePrefix).toUri())

            else -> emptySet()
        }

    private fun loadLangFiles(ownerLabel: String, directory: Path): Set<String> {
        if (!Files.isDirectory(directory)) return emptySet()

        return Files.list(directory).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .map { path -> loadLangFile(ownerLabel, path) }
                .toList()
                .filterNotNull()
                .toSet()
        }
    }

    private fun loadLangFile(ownerLabel: String, path: Path): String? {
        val name = path.fileName.toString()
        if (!name.endsWith(".json")) return null
        val locale = name.removeSuffix(".json")
        registerLangBundle(ownerLabel, locale, Files.readString(path))
        return locale
    }

    private fun loadLangOverrides(ownerLabel: String, namespace: String): Set<String> {
        val root = configDir?.resolve("lang")?.resolve(namespace) ?: return emptySet()
        return loadLangFiles(ownerLabel, root)
    }

    private fun registerLangBundle(ownerLabel: String, locale: String, json: String) {
        val entries = parseLangJson(json)
        entries.forEach { (key, value) ->
            Localization.register(locale, key, value)
        }
        logger.debug("Loaded {} translations for {} locale '{}'.", entries.size, ownerLabel, locale)
    }

    private fun parseLangJson(json: String): Map<String, String> {
        val parser = LangJsonParser(json)
        return parser.parseObject()
    }

    private class LangJsonParser(private val input: String) {
        private var index: Int = 0

        fun parseObject(): Map<String, String> {
            skipWhitespace()
            expect('{')
            skipWhitespace()
            val values = linkedMapOf<String, String>()
            if (peek() == '}') {
                index += 1
                return values
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                values[key] = parseString()
                skipWhitespace()
                when (val next = nextChar()) {
                    ',' -> skipWhitespace()
                    '}' -> return values
                    else -> error("Unexpected character '$next' while parsing lang json.")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                when (val ch = nextChar()) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(parseEscape())
                    else -> builder.append(ch)
                }
            }
        }

        private fun parseEscape(): Char = when (val escaped = nextChar()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> ''
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = input.substring(index, index + 4)
                index += 4
                hex.toInt(16).toChar()
            }

            else -> error("Unsupported JSON escape sequence: \\$escaped")
        }

        private fun expect(expected: Char) {
            val actual = nextChar()
            if (actual != expected) {
                error("Expected '$expected' but found '$actual'.")
            }
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) {
                index += 1
            }
        }

        private fun peek(): Char? = input.getOrNull(index)

        private fun nextChar(): Char {
            if (index >= input.length) error("Unexpected end of JSON input.")
            return input[index++]
        }
    }

    /** Validates that [Plugin.supportedApiVersions] includes [MoeMusicApi.API_VERSION]. */
    private fun requireCompatibleApiVersion(plugin: Plugin) {
        val range = plugin.supportedApiVersions
        val api = checkNotNull(SemVer.parse(MoeMusicApi.API_VERSION)) {
            "MoeMusic runtime reports invalid API compatibility version '${MoeMusicApi.API_VERSION}'."
        }
        check(matchesRange(api, range)) {
            "Plugin '${plugin.id}' v${plugin.version} requires API version '$range' but running " +
                    "${MoeMusicApi.API_VERSION}. Refusing to start with an incompatible plugin."
        }
    }

    /**
     * Validates that [Plugin.configId] satisfies `^[a-z0-9_-]+$`.
     *
     * The default implementation of [Plugin.configId] sanitizes [Plugin.id] automatically,
     * so only plugins that override [Plugin.configId] with an invalid value fail here.
     */
    private fun requireValidConfigId(plugin: Plugin) {
        val configId = plugin.configId
        check(CONFIG_ID_RE.matches(configId)) {
            "Plugin '${plugin.id}' has invalid configId '$configId' (must match ^[a-z0-9_-]+$). " +
                    "Refusing to start with an invalid plugin config file id."
        }
    }

    // -------------------------------------------------------------------------
    // Simple SemVer range checker
    // -------------------------------------------------------------------------

    private data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
        override fun compareTo(other: SemVer) =
            compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

        companion object {
            fun parse(s: String): SemVer? {
                val parts = s.trim().split(".")
                if (parts.size != 3) return null
                return try {
                    SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                } catch (_: NumberFormatException) {
                    null
                }
            }
        }
    }

    /** Supports: `*`, space-separated constraints like `>=0.1.0 <1.0.0`. Operators: `>=`, `>`, `<=`, `<`, `=`. */
    private fun matchesRange(version: SemVer, range: String): Boolean {
        if (range.isBlank() || range == "*") return true
        return range.trim().split("\\s+".toRegex()).all { constraint ->
            val op = listOf(">=", "<=", ">", "<", "=").find { constraint.startsWith(it) } ?: "="
            val target = SemVer.parse(constraint.removePrefix(op)) ?: return@all false
            when (op) {
                ">=" -> version >= target
                "<=" -> version <= target
                ">" -> version > target
                "<" -> version < target
                else -> version == target
            }
        }
    }
}
