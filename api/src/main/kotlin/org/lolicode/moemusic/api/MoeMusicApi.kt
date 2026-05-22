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

    @PublishedApi
    internal val _plugins: LinkedHashMap<String, Plugin> = linkedMapOf()

    /** Read-only view of all registered plugins in registration order. */
    public val plugins: List<Plugin>
        get() = _plugins.values.toList()

    /**
     * Register [plugin] with the MoeMusic runtime.
     *
     * Call this from your loader mod's `onInitialize`, `onInitializeClient`, or equivalent
     * initializer. `PluginManager` consumes explicit registrations lazily from its runtime
     * initialization hook, so registration only needs to happen before MoeMusic's runtime
     * initialization begins.
     *
     * @throws DuplicateRegistrationException if another plugin has already registered the same
     * [Plugin.id]. Duplicate plugin ids are fatal because plugin lookup and lifecycle dispatch
     * use the id as a global key.
     */
    public fun registerPlugin(plugin: Plugin) {
        val existing = _plugins[plugin.id]
        if (existing != null) {
            throw DuplicateRegistrationException(
                "Duplicate MoeMusic plugin id '${plugin.id}': ${describePlugin(existing)} is already registered; " +
                    "refusing to register ${describePlugin(plugin)}. Plugin ids must be globally unique.",
            )
        }
        _plugins[plugin.id] = plugin
    }

    private fun describePlugin(plugin: Plugin): String =
        "${plugin.javaClass.name} v${plugin.version}"
}
