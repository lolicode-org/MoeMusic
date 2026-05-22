package org.lolicode.moemusic.core.i18n

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.LocalizedTextArg
import org.lolicode.moemusic.core.config.DEFAULT_SERVER_LANGUAGE
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.normalizeLanguageId
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object Localization {

    private val logger = LoggerFactory.getLogger(Localization::class.java)

    /** locale -> (key -> value) */
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    fun normalizeLocale(locale: String): String =
        locale.trim().lowercase(Locale.ROOT).replace('-', '_')

    fun register(locale: String, key: String, value: String) {
        val normalized = normalizeLocale(locale)
        if (normalized.isBlank()) return
        store.getOrPut(normalized) { ConcurrentHashMap() }[key] = value
    }

    fun clear() {
        store.clear()
    }

    fun availableLocales(): Set<String> = store.keys.toSortedSet()

    fun hasLocale(locale: String): Boolean =
        store.containsKey(normalizeLocale(locale))

    fun resolveLocale(localeHint: String?): String {
        val normalized = localeHint?.let(::normalizeLocale).orEmpty()
        return normalized.ifBlank { defaultLocale() }
    }

    fun defaultLocale(): String {
        val configured = normalizeLanguageId(ModConfigManager.config.defaultLanguage)
        return if (hasLocale(configured)) configured else DEFAULT_SERVER_LANGUAGE
    }

    fun validateConfiguredDefaultLanguage(): String {
        val configured = ModConfigManager.config.defaultLanguage
        val validated = defaultLocale()
        if (validated != configured) {
            val available = availableLocales().joinToString().ifBlank { "<none>" }
            logger.warn(
                "Configured default_language '{}' has no loaded lang bundle; using '{}'. Available languages: {}",
                configured,
                validated,
                available,
            )
            ModConfigManager.update { config -> config.copy(defaultLanguage = validated) }
        }
        return validated
    }

    fun get(locale: String?, key: String): String {
        val requestedLocale = resolveLocale(locale)
        val defaultLocale = defaultLocale()
        return store[requestedLocale]?.get(key)
            ?: store[defaultLocale]?.get(key)
            ?: store[DEFAULT_SERVER_LANGUAGE]?.get(key)
            ?: key
    }

    fun getIfPresent(locale: String?, key: String): String? {
        val normalized = locale?.let(::normalizeLocale).orEmpty()
        if (normalized.isBlank()) return null
        return store[normalized]?.get(key)
    }

    fun render(locale: String?, text: LocalizedText): String = when (text) {
        is LocalizedText.Plain -> text.text
        is LocalizedText.Key -> formatLikeMinecraft(
            get(locale, text.key),
            text.args.map { arg ->
                when (arg) {
                    is LocalizedTextArg.Text -> render(locale, arg.value)
                    is LocalizedTextArg.Value -> arg.value
                }
            },
        )
    }

    private fun formatLikeMinecraft(template: String, args: List<String>): String {
        val pattern = Regex("%(?:(\\d+)\\$)?([A-Za-z%])")
        val result = StringBuilder()
        var nextSequentialIndex = 0
        var lastEnd = 0

        for (match in pattern.findAll(template)) {
            result.append(template, lastEnd, match.range.first)
            when (match.groupValues[2]) {
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
