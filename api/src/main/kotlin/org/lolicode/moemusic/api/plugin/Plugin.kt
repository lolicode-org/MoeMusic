package org.lolicode.moemusic.api.plugin

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicApi

/**
 * Entry point for a MoeMusic plugin.
 *
 * Standalone plugins are loaded from jar files under `config/moemusic/plugins/` through
 * [PluginProvider].
 * Plugins that are also Minecraft loader mods may instead register themselves via
 * [MoeMusicApi.registerPlugin] in their mod initializer. Either bootstrap path only requires this
 * `:api` module for the MoeMusic contract; loader mods may additionally depend on their target
 * modloader when they need loader-specific APIs.
 *
 * Lifecycle guarantees:
 * - [onServerRuntimeLoad] is called once per logical server runtime in the current JVM, after
 *   MoeMusic finishes its persistent server-runtime initialization and before the first
 *   [onServerSessionLoad]. This is the place for source registration, i18n, long-lived event
 *   subscriptions, and config listeners. On a client JVM that only joins third-party servers,
 *   no logical server runtime exists locally, so this callback may never fire.
 * - [onServerSessionLoad] is called once per Minecraft server session before the server accepts
 *   connections. In integrated singleplayer this may happen multiple times after one
 *   [onServerRuntimeLoad].
 * - [onServerSessionUnload] is called once per Minecraft server session when that session shuts down.
 * - [onServerRuntimeUnload] is called when the logical server runtime is torn down for the JVM
 *   (dedicated server exit or full client shutdown).
 * - [onClientRuntimeLoad] is called once per client runtime after MoeMusic finishes its
 *   client-side initialization. It does not imply that a logical server runtime also exists in
 *   that JVM.
 * - [onClientRuntimeUnload] is called on client shutdown only.
 * - **No hot-reload.** Plugins are never unloaded and reloaded within a single JVM session.
 *
 * If a plugin needs to work on both the client runtime and the logical server runtime, initialize
 * the client-side state in [onClientRuntimeLoad] and the logical-server state in
 * [onServerRuntimeLoad]. They are separate lifetimes and either one may be absent on a given JVM.
 */
public interface Plugin {

    /**
     * Stable, unique identifier for this plugin (e.g. `"my-awesome-source"`).
     * Used as the log tag and (unless [configId] is overridden) derives the config file name.
     *
     * Plugin ids are global across the running JVM. Registering two plugins with the same id
     * is a fatal startup error.
     */
    public val id: String

    /**
     * Human-readable display name for this plugin, shown as the tab label in the config screen.
     *
     * Defaults to [id]. Prefer [LocalizedText.key] backed by bundled lang resources so the label
     * can be rendered in the current client locale.
     */
    public val displayName: LocalizedText
        get() = LocalizedText.plain(id)

    /**
     * Platform-agnostic config schema for this plugin's single TOML config file.
     *
     * When present, MoeMusic creates the default config file during plugin manager
     * initialization if it does not already exist. Platform modules can also auto-generate
     * a settings screen for the plugin without the plugin depending on Cloth Config or any
     * other UI library.
     */
    public val configSpec: PluginConfigSpec<*>?
        get() = null

    /**
     * Filesystem-safe identifier used as the base name for this plugin's config file.
     *
     * Must match `^[a-z0-9_-]+$`. Defaults to [id] with any character outside that set
     * replaced by `_`, which handles common namespace separators such as `:` and `/`.
     *
     * Plugins with namespaced IDs (e.g. `"moemusic:content_filter"`) should override this
     * to an explicit, stable name (e.g. `"moemusic_content_filter"`) so the config file name
     * is predictable and portable across all operating systems (Windows forbids `:` in paths).
     *
     * The plugin manager treats an invalid [configId] as a fatal startup error and aborts
     * initialization so the problem is visible immediately.
     */
    public val configId: String
        get() = id.replace(Regex("[^a-z0-9_-]"), "_")

    /** Human-readable version string (e.g. `"1.2.3"`). */
    public val version: String

    /**
     * SemVer range expression describing compatible MoeMusic plugin API versions
     * (e.g. `">=0.1.0 <1.0.0"`). The plugin manager treats an incompatible range as a fatal
     * startup error and aborts initialization so the mismatch cannot be missed.
     *
     * This range is evaluated against `MoeMusicApi.API_VERSION`, the stable API compatibility
     * version, not the Maven artifact version. Snapshot API artifacts should therefore keep the
     * same compatibility version unless the plugin API contract itself changes.
     */
    public val supportedApiVersions: String

    /** Called once when the persistent logical server runtime is initialized in the JVM. */
    public fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {}

    /** Called when a concrete Minecraft server session starts. */
    public fun onServerSessionLoad(ctx: ServerSessionContext) {}

    /**
     * Called on the **client** side when the client runtime initializes.
     * Register client-side event subscribers here.
     */
    public fun onClientRuntimeLoad(ctx: ClientRuntimeContext) {}

    /** Called when a Minecraft server session shuts down. Release session-bound resources here. */
    public fun onServerSessionUnload() {}

    /** Called when the persistent logical server runtime is torn down for the JVM. */
    public fun onServerRuntimeUnload() {}

    /** Called when the client shuts down normally. Release client-side resources here. */
    public fun onClientRuntimeUnload() {}
}
