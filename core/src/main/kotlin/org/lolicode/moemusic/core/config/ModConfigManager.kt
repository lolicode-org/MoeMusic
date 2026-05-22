package org.lolicode.moemusic.core.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.lolicode.moemusic.api.model.ContentFilterRules
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Manages the main MoeMusic mod config file (`<configDir>/moemusic.toml`).
 *
 * Uses ktoml for TOML serialization. Call [load] during startup for the current runtime
 * (client or server), then prefer [update] / subsection helpers when mutating only part of the
 * config so newer in-memory values are preserved.
 */
object ModConfigManager {

    private val logger = LoggerFactory.getLogger(ModConfigManager::class.java)
    private val lock = Any()

    private val toml = Toml(
        inputConfig = TomlInputConfig(ignoreUnknownNames = true),
        outputConfig = TomlOutputConfig(),
    )

    @Volatile
    private var _config: MoeMusicConfig = MoeMusicConfig()

    /** Current in-memory configuration. Updated on [load] and [save]. */
    val config: MoeMusicConfig get() = _config

    private lateinit var configFile: Path

    /**
     * Load configuration from [configDir]/moemusic.toml.
     *
     * If the file does not exist, a default [MoeMusicConfig] is used and immediately
     * written to disk so the file is visible and editable.
     *
     * @param configDir Root mod config directory (must exist or be creatable).
     */
    fun load(configDir: Path) {
        synchronized(lock) {
            configFile = configDir.resolve("moemusic.toml")
            _config = readConfigLocked(fallbackToDefaultsOnError = true)
            // Always persist so missing keys are written with their defaults.
            persistLocked(_config)
        }
    }

    /**
     * Re-read `moemusic.toml` from disk without discarding the current in-memory config on parse
     * failure.
     *
     * Missing files still fall back to defaults and are persisted immediately so the admin can
     * see the generated file again.
     *
     * @throws IllegalStateException when the file exists but cannot be deserialized.
     */
    fun reload(configDir: Path): MoeMusicConfig = synchronized(lock) {
        configFile = configDir.resolve("moemusic.toml")
        _config = readConfigLocked(fallbackToDefaultsOnError = false)
        persistLocked(_config)
        _config
    }

    /**
     * Strictly read `moemusic.toml` from disk without mutating the current in-memory config.
     *
     * Missing files still resolve to the normalized default config.
     *
     * @throws IllegalStateException when the file exists but cannot be deserialized.
     */
    fun readFromDisk(configDir: Path): MoeMusicConfig = synchronized(lock) {
        readConfigFile(configDir.resolve("moemusic.toml"), fallbackToDefaultsOnError = false)
    }

    /**
     * Update in-memory config to [newConfig] and persist it.
     */
    fun save(newConfig: MoeMusicConfig = _config): MoeMusicConfig = synchronized(lock) {
        _config = newConfig.normalized()
        persistLocked(_config)
        _config
    }

    /**
     * Re-read the latest in-memory config under a lock, transform it, then persist the result.
     *
     * Prefer this over rebuilding a whole [MoeMusicConfig] from a stale snapshot when only part
     * of the file is being edited.
     */
    fun update(transform: (MoeMusicConfig) -> MoeMusicConfig): MoeMusicConfig = synchronized(lock) {
        _config = transform(_config).normalized()
        persistLocked(_config)
        _config
    }

    /**
     * Update only the client-local config subsection against the latest root config.
     */
    fun updateClient(transform: (ClientConfig) -> ClientConfig): MoeMusicConfig =
        update { current -> current.copy(client = transform(current.client)) }

    /**
     * Update only the authoritative shared content-filter subsection against the latest root config.
     */
    fun updateContentFilter(transform: (ContentFilterRules) -> ContentFilterRules): MoeMusicConfig =
        update { current -> current.copy(contentFilter = transform(current.contentFilter)) }

    /**
     * Replace only the in-memory config snapshot without writing the file back to disk.
     *
     * Use this when a command intentionally wants to apply one disk-loaded subsection without
     * clobbering unrelated edits already present in the file.
     */
    fun replaceInMemory(transform: (MoeMusicConfig) -> MoeMusicConfig): MoeMusicConfig = synchronized(lock) {
        _config = transform(_config).normalized()
        _config
    }

    private fun persistLocked(config: MoeMusicConfig) {
        try {
            configFile.parent?.createDirectories()
            configFile.writeText(toml.encodeToString(config))
        } catch (e: Exception) {
            logger.error("Failed to save moemusic.toml: {}", e.message)
        }
    }

    private fun readConfigLocked(fallbackToDefaultsOnError: Boolean): MoeMusicConfig =
        readConfigFile(configFile, fallbackToDefaultsOnError)

    private fun readConfigFile(file: Path, fallbackToDefaultsOnError: Boolean): MoeMusicConfig {
        if (!file.exists()) return MoeMusicConfig()
        return try {
            toml.decodeFromString<MoeMusicConfig>(file.readText()).normalized()
        } catch (e: Exception) {
            if (fallbackToDefaultsOnError) {
                logger.warn("Failed to load moemusic.toml, using defaults: {}", e.message)
                MoeMusicConfig()
            } else {
                throw IllegalStateException("Failed to parse moemusic.toml: ${e.message}", e)
            }
        }
    }
}
