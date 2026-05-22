package org.lolicode.moemusic.core.plugin

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializerOrNull
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.PluginConfigSpec
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.reflect.KClass

/**
 * Lightweight TOML config I/O helper.
 *
 * Encapsulates ktoml so that callers in modules without a direct ktoml compile dependency
 * (e.g. the client source set of `:platform-common`) can still load and save
 * `@Serializable` config data classes at runtime.
 */
object PluginConfigIO {

    private val logger = LoggerFactory.getLogger(PluginConfigIO::class.java)
    private const val PLUGIN_CONFIG_DIR = "plugin-configs"
    private const val PLUGIN_DATA_DIR = "plugin-data"

    private val toml = Toml(
        inputConfig = TomlInputConfig(ignoreUnknownNames = true),
        outputConfig = TomlOutputConfig(),
    )

    /** Resolve the config file path for [plugin] under [rootConfigDir]. */
    fun fileFor(rootConfigDir: Path, plugin: Plugin): Path =
        pluginConfigDir(rootConfigDir).resolve("${plugin.configId}.toml")

    /** Resolve the custom data directory path for [plugin] under [rootConfigDir]. */
    fun dataDirFor(rootConfigDir: Path, plugin: Plugin): Path =
        pluginDataDir(rootConfigDir).resolve(plugin.configId)

    fun pluginConfigDir(rootConfigDir: Path): Path =
        rootConfigDir.resolve(PLUGIN_CONFIG_DIR)

    fun pluginDataDir(rootConfigDir: Path): Path =
        rootConfigDir.resolve(PLUGIN_DATA_DIR)

    /**
     * Create [file] with this plugin spec's default value when it is missing.
     *
     * Existing files are intentionally left untouched so startup never overwrites operator edits
     * or replaces a malformed file before a reload path can report the parse failure.
     */
    fun ensureExists(file: Path, spec: PluginConfigSpec<*>) {
        if (file.exists()) return
        saveAny(file, spec.createDefault(), spec)
    }

    /**
     * Load a typed config object from [file].
     *
     * Returns [default] if the file does not exist, cannot be read, or fails to deserialize.
     *
     * @param file    Absolute path to the TOML config file.
     * @param clazz   KClass of the `@Serializable` config data class.
     * @param default Factory for a default instance when no valid file is present.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> load(file: Path, clazz: KClass<T>, default: () -> T): T {
        val serializer = serializerOrNull(clazz.java) as? KSerializer<T>
            ?: run {
                logger.warn("PluginConfigIO.load: no @Serializable serializer for {}; returning default.", clazz.simpleName)
                return default()
            }
        if (!file.exists()) return default()
        return try {
            toml.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            logger.warn("PluginConfigIO.load: failed to parse {} as {}: {}", file, clazz.simpleName, e.message)
            default()
        }
    }

    /** Type-erased overload used by generic config-screen rendering. */
    @Suppress("UNCHECKED_CAST")
    fun loadAny(file: Path, spec: PluginConfigSpec<*>): Any =
        load(file, spec.configClass as KClass<Any>) { spec.createDefault() }

    /**
     * Strict typed reload helper used by server-side config reload paths.
     *
     * Unlike [load], parse failures are surfaced to the caller so the existing runtime state can
     * stay intact instead of silently reverting to defaults.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> loadStrict(file: Path, clazz: KClass<T>, default: () -> T): T {
        val serializer = serializerOrNull(clazz.java) as? KSerializer<T>
            ?: throw IllegalStateException(
                "PluginConfigIO.loadStrict: no @Serializable serializer for ${clazz.simpleName}.",
            )
        if (!file.exists()) return default()
        return try {
            toml.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse ${file.fileName}: ${e.message}", e)
        }
    }

    /** Type-erased strict overload used by generic reload flows. */
    @Suppress("UNCHECKED_CAST")
    fun loadAnyStrict(file: Path, spec: PluginConfigSpec<*>): Any =
        loadStrict(file, spec.configClass as KClass<Any>) { spec.createDefault() }

    /**
     * Persist a typed config object to [file].
     *
     * Parent directories are created automatically. Failures are logged and silently swallowed
     * so a save error never crashes the caller (e.g. the config screen).
     *
     * @param file  Absolute path to the TOML config file.
     * @param value The config object to serialize.
     * @param clazz KClass of the `@Serializable` config data class.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> save(file: Path, value: T, clazz: KClass<T>) {
        val serializer = serializerOrNull(clazz.java) as? KSerializer<T>
            ?: run {
                logger.warn("PluginConfigIO.save: no @Serializable serializer for {}; skipping.", clazz.simpleName)
                return
            }
        try {
            file.parent?.createDirectories()
            file.writeText(toml.encodeToString(serializer, value))
        } catch (e: Exception) {
            logger.error("PluginConfigIO.save: failed to write {}: {}", file, e.message)
        }
    }

    /** Type-erased overload used by generic config-screen rendering. */
    @Suppress("UNCHECKED_CAST")
    fun saveAny(file: Path, value: Any, spec: PluginConfigSpec<*>) {
        save(file, value, spec.configClass as KClass<Any>)
    }
}
