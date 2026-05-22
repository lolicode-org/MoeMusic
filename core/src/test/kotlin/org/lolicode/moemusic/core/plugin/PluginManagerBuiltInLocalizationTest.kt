package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.core.i18n.Localization
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PluginManagerBuiltInLocalizationTest {

    @BeforeTest
    fun resetStateBeforeTest() {
        resetPluginTestState()
    }

    @AfterTest
    fun resetStateAfterTest() {
        resetPluginTestState()
    }

    @Test
    fun `built in moemusic lang bundles load without any plugins`() {
        val tmpDir = Files.createTempDirectory("moemusic-plugin-manager-i18n-test")

        PluginManager.initialize(tmpDir)

        val english = Localization.render("en_us", LocalizedText.key("source.moemusic.http"))
        val chinese = Localization.render("zh_cn", LocalizedText.key("source.moemusic.http"))

        assertNotEquals("source.moemusic.http", english)
        assertNotEquals("source.moemusic.http", chinese)
        assertEquals("Direct Link", english)
        assertEquals("直链 URL", chinese)
    }
}
