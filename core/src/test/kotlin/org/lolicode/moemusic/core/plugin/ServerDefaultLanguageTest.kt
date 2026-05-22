package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.i18n.Localization
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerDefaultLanguageTest {

    @AfterTest
    fun resetState() {
        PluginManager.reset()
        Localization.clear()
        ModConfigManager.load(Files.createTempDirectory("moemusic-default-language-reset"))
    }

    @Test
    fun `server rendering falls back to configured default language`() {
        val configDir = Files.createTempDirectory("moemusic-default-language-render-test")
        ModConfigManager.load(configDir)
        Localization.register("en_us", "test.moemusic.message", "English")
        Localization.register("zh_cn", "test.moemusic.message", "Chinese")

        ModConfigManager.update { it.copy(defaultLanguage = "zh_cn") }

        assertEquals(
            "Chinese",
            Localization.render("missing_locale", LocalizedText.key("test.moemusic.message")),
        )
    }

    @Test
    fun `configured default language is validated against loaded bundles`() {
        val configDir = Files.createTempDirectory("moemusic-default-language-validation-test")
        ModConfigManager.load(configDir)
        Localization.register("en_us", "test.moemusic.message", "English")

        ModConfigManager.update { it.copy(defaultLanguage = "zh_cn") }
        val validated = Localization.validateConfiguredDefaultLanguage()

        assertEquals("en_us", validated)
        assertEquals("en_us", ModConfigManager.config.defaultLanguage)
    }
}
