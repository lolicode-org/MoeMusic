package org.lolicode.moemusic.core.i18n

import org.lolicode.moemusic.api.I18nRegistry

internal object PluginI18nRegistry : I18nRegistry {
    override fun normalizeLocale(locale: String): String =
        Localization.normalizeLocale(locale)

    override fun register(locale: String, key: String, value: String) {
        Localization.register(locale, key, value)
    }

    override fun get(locale: String, key: String): String =
        Localization.get(locale, key)
}
