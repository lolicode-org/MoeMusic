package org.lolicode.moemusic.api

/**
 * Registry for plugin-provided localized strings.
 *
 * Plugins register translations during load, usually from bundled `assets/<namespace>/lang`
 * resources and optional filesystem overrides supplied by the server owner. Core stores the raw
 * translations here, then renders [LocalizedText] at user-facing boundaries using the relevant
 * player locale.
 *
 * Plugin guidance:
 * - prefer shipping bundled lang files under `assets/<namespace>/lang/<locale>.json`
 * - use [register] during load only when translations must be added programmatically
 * - pass [LocalizedText.Key] through your APIs instead of calling [render] eagerly
 * - use Minecraft-style `%s`, `%1$s`, `%2$s` placeholders in translation strings
 * - reserve [LocalizedText.Plain] for text that is already final and should not be translated
 *
 * MoeMusic's runtime renderer falls back to the configured server default language when a
 * requested locale has no matching key, then to `en_us`; unknown keys fall back to the key itself.
 */
public interface I18nRegistry {
    public fun normalizeLocale(locale: String): String = locale.lowercase().replace('-', '_')

    /**
     * Register a translation entry.
     *
     * @param locale Locale identifier in lower-case BCP-47 form (e.g. `"en_us"`, `"zh_cn"`).
     * @param key    Dot-separated message key (e.g. `"myplugin.filter.blocked"`).
     * @param value  Human-readable translated string. May contain Minecraft-style `%s`, `%1$s` … placeholders.
     */
    public fun register(locale: String, key: String, value: String)

    /**
     * Look up a translated string.
     *
     * MoeMusic's built-in registry falls back to the configured server default language if
     * [locale] has no entry for [key], then to `en_us`, then to returning [key] itself if no
     * translation is found at all.
     */
    public fun get(locale: String, key: String): String

    /** Convenience overload: format the resolved string with Minecraft-style `%s` / `%1$s` placeholders. */
    public fun getFormatted(locale: String, key: String, vararg args: Any): String =
        formatLikeMinecraft(get(locale, key), args.map { it.toString() })

    public fun render(locale: String, text: LocalizedText): String = when (text) {
        is LocalizedText.Plain -> text.text
        is LocalizedText.Key -> getFormatted(
            locale,
            text.key,
            *text.args.map { arg ->
                when (arg) {
                    is LocalizedTextArg.Text -> render(locale, arg.value)
                    is LocalizedTextArg.Value -> arg.value
                }
            }.toTypedArray(),
        )
    }

    private fun formatLikeMinecraft(template: String, args: List<String>): String {
        val pattern = Regex("%(?:(\\d+)\\$)?([A-Za-z%])")
        val result = StringBuilder()
        var nextSequentialIndex = 0
        var lastEnd = 0

        for (match in pattern.findAll(template)) {
            result.append(template, lastEnd, match.range.first)
            val formatType = match.groupValues[2]
            when (formatType) {
                "%" -> result.append('%')
                "s" -> {
                    val explicitIndex = match.groupValues[1].takeIf(String::isNotEmpty)?.toInt()?.minus(1)
                    val index = explicitIndex ?: nextSequentialIndex++
                    result.append(args.getOrNull(index) ?: match.value)
                }
                else -> result.append(match.value)
            }
            lastEnd = match.range.last + 1
        }

        result.append(template, lastEnd, template.length)
        return result.toString()
    }
}
