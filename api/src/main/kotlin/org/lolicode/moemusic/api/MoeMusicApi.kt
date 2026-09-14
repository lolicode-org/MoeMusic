package org.lolicode.moemusic.api

import org.lolicode.moemusic.api.plugin.Plugin

/**
 * Public entry point for explicit plugin registration.
 *
 * Minecraft loader mods may call [registerPlugin] in their mod initializer when they want the
 * platform loader to bootstrap their MoeMusic plugin. Standalone plugins that do not need Fabric,
 * NeoForge, or Minecraft bootstrap should instead expose a
 * [org.lolicode.moemusic.api.plugin.PluginProvider] service descriptor in their plugin jar.
 *
 * This object lives in `:api` (zero Minecraft dependency) so any caller that has `:api`
 * on its classpath can register without depending on `:core` or `:platform-common`.
 */
public object MoeMusicApi {

    /**
     * The MoeMusic plugin API compatibility version.
     *
     * Plugins check this against [Plugin.supportedApiVersions]. This is intentionally separate
     * from the Maven artifact version, so snapshot artifacts such as `1.0.1-SNAPSHOT` can still
     * report the stable API compatibility version they implement.
     */
    public val API_VERSION: String
        get() = MoeMusicApiBuildInfo.API_VERSION

    private val _plugins: MutableList<Plugin> = mutableListOf()

    /** Read-only view of all registered plugin candidates in registration order. */
    public val plugins: List<Plugin>
        get() = synchronized(_plugins) { _plugins.toList() }

    /**
     * Register [plugin] with the MoeMusic runtime.
     *
     * Call this from your loader mod's `onInitialize`, `onInitializeClient`, or equivalent
     * initializer. `PluginManager` consumes explicit registrations lazily from its runtime
     * initialization hook, so registration only needs to happen before MoeMusic's runtime
     * initialization begins.
     *
     * If multiple candidates register the same [Plugin.id], `PluginManager` will evaluate them
     * during initialization, select the highest compatible version, and report duplicates or
     * incompatibilities to the platform.
     */
    public fun registerPlugin(plugin: Plugin) {
        synchronized(_plugins) {
            _plugins.add(plugin)
        }
    }
}
