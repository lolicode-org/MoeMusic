package org.lolicode.moemusic.api.plugin

/**
 * Service-provider entry point for standalone MoeMusic plugin jars.
 *
 * A standalone plugin jar placed under `config/moemusic/plugins/` must provide a
 * `META-INF/services/org.lolicode.moemusic.api.plugin.PluginProvider` file listing one or more
 * public provider classes. MoeMusic constructs each provider with a public no-argument
 * constructor and loads the returned [Plugin] instances during runtime initialization.
 *
 * Plugins that are also Minecraft loader mods may keep using
 * `MoeMusicApi.registerPlugin(...)` from their loader initializer instead.
 */
public interface PluginProvider {

    /** Return every [Plugin] exposed by this provider. */
    public fun plugins(): Iterable<Plugin>
}
