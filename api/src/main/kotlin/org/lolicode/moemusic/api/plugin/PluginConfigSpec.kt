package org.lolicode.moemusic.api.plugin

import org.lolicode.moemusic.api.LocalizedText
import kotlin.reflect.KClass

/**
 * Platform-agnostic description of a plugin's single typed config file.
 *
 * Plugins expose one [PluginConfigSpec] via [Plugin.configSpec]. Core uses it to load/save
 * the TOML file, and platform modules can render a config UI from the declared entries
 * without the plugin depending on Cloth Config or any other loader-specific API.
 */
public class PluginConfigSpec<T : Any> public constructor(
    public val configClass: KClass<T>,
    private val defaultFactory: () -> T,
    public val entries: List<PluginConfigEntry>,
) {
    /** Create a fresh default config instance. */
    public fun createDefault(): T = defaultFactory()
}

/**
 * One generated config-screen entry inside a [PluginConfigSpec].
 *
 * Implementations are intentionally type-erased at the API boundary so platform modules can
 * render them without needing to know the plugin config's concrete type.
 */
public sealed interface PluginConfigEntry {

    /** Stable entry key, typically matching the serialized TOML field name. */
    public val key: String

    /** Read this entry's current value from [config]. */
    public fun read(config: Any): Any

    /** Return a new config object with this entry updated to [value]. */
    public fun write(config: Any, value: Any): Any

    /**
     * Validate [value] against the config object produced by applying it to [config].
     *
     * Returns `null` when the candidate config is valid, or a localized player/admin-facing
     * message when it is not. Validators should not throw for ordinary invalid input.
     */
    public fun validate(config: Any, value: Any): LocalizedText?
}

public abstract class BasePluginConfigEntry<T : Any, V : Any>(
    override val key: String,
    private val getter: (T) -> V,
    private val updater: (T, V) -> T,
    private val validator: (T, V) -> LocalizedText?,
) : PluginConfigEntry {

    @Suppress("UNCHECKED_CAST")
    final override fun read(config: Any): Any = getter(config as T)

    @Suppress("UNCHECKED_CAST")
    final override fun write(config: Any, value: Any): Any = updater(config as T, value as V)

    @Suppress("UNCHECKED_CAST")
    final override fun validate(config: Any, value: Any): LocalizedText? {
        val typedConfig = config as T
        val typedValue = value as V
        val candidate = updater(typedConfig, typedValue)
        return validator(candidate, typedValue)
    }
}

/** Boolean config entry. */
public class BooleanPluginConfigEntry<T : Any> public constructor(
    key: String,
    getter: (T) -> Boolean,
    updater: (T, Boolean) -> T,
    validator: (T, Boolean) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, Boolean>(key, getter, updater, validator)

/** Integer config entry. */
public class IntPluginConfigEntry<T : Any> public constructor(
    key: String,
    getter: (T) -> Int,
    updater: (T, Int) -> T,
    validator: (T, Int) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, Int>(key, getter, updater, validator)

/** Integer slider config entry. */
public class IntSliderPluginConfigEntry<T : Any> public constructor(
    key: String,
    public val min: Int,
    public val max: Int,
    getter: (T) -> Int,
    updater: (T, Int) -> T,
    validator: (T, Int) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, Int>(key, getter, updater, validator)

/** Long config entry. */
public class LongPluginConfigEntry<T : Any> public constructor(
    key: String,
    getter: (T) -> Long,
    updater: (T, Long) -> T,
    validator: (T, Long) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, Long>(key, getter, updater, validator)

/** Long slider config entry. */
public class LongSliderPluginConfigEntry<T : Any> public constructor(
    key: String,
    public val min: Long,
    public val max: Long,
    getter: (T) -> Long,
    updater: (T, Long) -> T,
    validator: (T, Long) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, Long>(key, getter, updater, validator)

/** Float config entry. */
public class FloatPluginConfigEntry<T : Any> public constructor(
    key: String,
    getter: (T) -> Float,
    updater: (T, Float) -> T,
    validator: (T, Float) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, Float>(key, getter, updater, validator)

/** Double config entry. */
public class DoublePluginConfigEntry<T : Any> public constructor(
    key: String,
    getter: (T) -> Double,
    updater: (T, Double) -> T,
    validator: (T, Double) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, Double>(key, getter, updater, validator)

/** String config entry. */
public class StringPluginConfigEntry<T : Any> public constructor(
    key: String,
    getter: (T) -> String,
    updater: (T, String) -> T,
    validator: (T, String) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, String>(key, getter, updater, validator)

/** String-list config entry. */
public class StringListPluginConfigEntry<T : Any> public constructor(
    key: String,
    getter: (T) -> List<String>,
    updater: (T, List<String>) -> T,
    validator: (T, List<String>) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, List<String>>(key, getter, updater, validator)

/** Enum config entry. */
public class EnumPluginConfigEntry<T : Any, E : Enum<E>> public constructor(
    key: String,
    public val enumClass: Class<E>,
    getter: (T) -> E,
    updater: (T, E) -> T,
    validator: (T, E) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, E>(key, getter, updater, validator)

/** Enum dropdown config entry for larger candidate sets. */
public class EnumDropdownPluginConfigEntry<T : Any, E : Enum<E>> public constructor(
    key: String,
    public val enumClass: Class<E>,
    getter: (T) -> E,
    updater: (T, E) -> T,
    validator: (T, E) -> LocalizedText? = { _, _ -> null },
) : BasePluginConfigEntry<T, E>(key, getter, updater, validator)

/** Builder used by [pluginConfigSpec]. */
public class PluginConfigSpecBuilder<T : Any> public constructor() {

    private val entries: MutableList<PluginConfigEntry> = mutableListOf()

    /** Add a boolean entry. */
    public fun boolean(
        key: String,
        getter: (T) -> Boolean,
        updater: (T, Boolean) -> T,
        validator: (T, Boolean) -> LocalizedText? = { _, _ -> null },
    ) {
        add(BooleanPluginConfigEntry(key, getter, updater, validator))
    }

    /** Add an integer entry. */
    public fun int(
        key: String,
        getter: (T) -> Int,
        updater: (T, Int) -> T,
        validator: (T, Int) -> LocalizedText? = { _, _ -> null },
    ) {
        add(IntPluginConfigEntry(key, getter, updater, validator))
    }

    /** Add an integer slider entry. */
    public fun intSlider(
        key: String,
        min: Int,
        max: Int,
        getter: (T) -> Int,
        updater: (T, Int) -> T,
        validator: (T, Int) -> LocalizedText? = { _, _ -> null },
    ) {
        add(IntSliderPluginConfigEntry(key, min, max, getter, updater, validator))
    }

    /** Add a long entry. */
    public fun long(
        key: String,
        getter: (T) -> Long,
        updater: (T, Long) -> T,
        validator: (T, Long) -> LocalizedText? = { _, _ -> null },
    ) {
        add(LongPluginConfigEntry(key, getter, updater, validator))
    }

    /** Add a long slider entry. */
    public fun longSlider(
        key: String,
        min: Long,
        max: Long,
        getter: (T) -> Long,
        updater: (T, Long) -> T,
        validator: (T, Long) -> LocalizedText? = { _, _ -> null },
    ) {
        add(LongSliderPluginConfigEntry(key, min, max, getter, updater, validator))
    }

    /** Add a float entry. */
    public fun float(
        key: String,
        getter: (T) -> Float,
        updater: (T, Float) -> T,
        validator: (T, Float) -> LocalizedText? = { _, _ -> null },
    ) {
        add(FloatPluginConfigEntry(key, getter, updater, validator))
    }

    /** Add a double entry. */
    public fun double(
        key: String,
        getter: (T) -> Double,
        updater: (T, Double) -> T,
        validator: (T, Double) -> LocalizedText? = { _, _ -> null },
    ) {
        add(DoublePluginConfigEntry(key, getter, updater, validator))
    }

    /** Add a string entry. */
    public fun string(
        key: String,
        getter: (T) -> String,
        updater: (T, String) -> T,
        validator: (T, String) -> LocalizedText? = { _, _ -> null },
    ) {
        add(StringPluginConfigEntry(key, getter, updater, validator))
    }

    /** Add a string-list entry. */
    public fun stringList(
        key: String,
        getter: (T) -> List<String>,
        updater: (T, List<String>) -> T,
        validator: (T, List<String>) -> LocalizedText? = { _, _ -> null },
    ) {
        add(StringListPluginConfigEntry(key, getter, updater, validator))
    }

    /** Add an enum selector entry. */
    public fun <E : Enum<E>> enumSelector(
        key: String,
        enumClass: KClass<E>,
        getter: (T) -> E,
        updater: (T, E) -> T,
        validator: (T, E) -> LocalizedText? = { _, _ -> null },
    ) {
        add(EnumPluginConfigEntry(key, enumClass.java, getter, updater, validator))
    }

    /** Reified convenience overload for [enumSelector]. */
    public inline fun <reified E : Enum<E>> enumSelector(
        key: String,
        noinline getter: (T) -> E,
        noinline updater: (T, E) -> T,
        noinline validator: (T, E) -> LocalizedText? = { _, _ -> null },
    ) {
        enumSelector(key, E::class, getter, updater, validator)
    }

    /** Add an enum dropdown entry. */
    public fun <E : Enum<E>> enumDropdown(
        key: String,
        enumClass: KClass<E>,
        getter: (T) -> E,
        updater: (T, E) -> T,
        validator: (T, E) -> LocalizedText? = { _, _ -> null },
    ) {
        add(EnumDropdownPluginConfigEntry(key, enumClass.java, getter, updater, validator))
    }

    /** Reified convenience overload for [enumDropdown]. */
    public inline fun <reified E : Enum<E>> enumDropdown(
        key: String,
        noinline getter: (T) -> E,
        noinline updater: (T, E) -> T,
        noinline validator: (T, E) -> LocalizedText? = { _, _ -> null },
    ) {
        enumDropdown(key, E::class, getter, updater, validator)
    }

    internal fun build(configClass: KClass<T>, defaultFactory: () -> T): PluginConfigSpec<T> =
        PluginConfigSpec(configClass, defaultFactory, entries.toList())

    private fun add(entry: PluginConfigEntry) {
        require(entry.key.isNotBlank()) { "Plugin config entry key must not be blank." }
        require(entries.none { it.key == entry.key }) {
            "Duplicate plugin config entry key '${entry.key}'."
        }
        entries.add(entry)
    }
}

/**
 * Create a [PluginConfigSpec] for [configClass].
 *
 * Entry translation keys are convention-based:
 * - Label: `config.<plugin-id-with-separators-replaced>.<entry-key>`
 * - Tooltip: same key with `.tooltip` suffix
 * - Enum values: `config.<plugin-id-with-separators-replaced>.<entry-key>.<enum-value-lowercase>`
 */
public fun <T : Any> pluginConfigSpec(
    configClass: KClass<T>,
    defaultFactory: () -> T,
    build: PluginConfigSpecBuilder<T>.() -> Unit,
): PluginConfigSpec<T> {
    val builder = PluginConfigSpecBuilder<T>()
    return builder.apply(build).build(configClass, defaultFactory)
}

/** Reified convenience overload for [pluginConfigSpec]. */
public inline fun <reified T : Any> pluginConfigSpec(
    noinline defaultFactory: () -> T,
    noinline build: PluginConfigSpecBuilder<T>.() -> Unit,
): PluginConfigSpec<T> = pluginConfigSpec(T::class, defaultFactory, build)
