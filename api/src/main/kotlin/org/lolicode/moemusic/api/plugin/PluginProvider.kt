package org.lolicode.moemusic.api.plugin

/**
 * Service-provider entry point for standalone MoeMusic plugin jars.
 *
 * A standalone plugin jar placed under `config/moemusic/plugins/` must provide a
 * `META-INF/services/org.lolicode.moemusic.api.plugin.PluginProvider` file listing one or more
 * public provider classes. MoeMusic constructs each provider with a public no-argument
 * constructor and loads the returned [Plugin] instances during runtime initialization.
 *
 * Plugins that are also Minecraft loader mods may also call
 * `MoeMusicApi.registerPlugin(...)` from their loader initializer.
 *
 * **Important:** Provider constructors and [plugins] should be pure and free of
 * side effects. Do not start threads, perform file I/O, or modify global state during provider
 * instantiation or plugin enumeration. Candidate plugins that are unselected duplicates or
 * incompatible are discarded during discovery, and their classloaders are closed before any
 * runtime lifecycle events fire.
 */
public interface PluginProvider {

    /** Return every [Plugin] exposed by this provider. */
    public fun plugins(): Iterable<Plugin>
}
