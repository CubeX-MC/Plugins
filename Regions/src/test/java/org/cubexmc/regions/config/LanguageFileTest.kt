package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class LanguageFileTest {
    @Test
    fun `shipped locales expose exactly the same keys`() {
        val chinese = keys("/lang/zh_CN.yml")
        val english = keys("/lang/en_US.yml")

        // A key present in one locale but not the other renders as the raw key path in game, so the
        // two files have to move together.
        assertEquals(emptySet<String>(), chinese - english, "keys missing from en_US")
        assertEquals(emptySet<String>(), english - chinese, "keys missing from zh_CN")
    }

    @Test
    fun `both locales match the declared baseline version`() {
        val expected = RegionBaseline.files.single { it.path == "lang/zh_CN.yml" }.version
        for (path in listOf("/lang/zh_CN.yml", "/lang/en_US.yml")) {
            assertEquals(expected, load(path).getInt("lang-version"), path)
        }
    }

    @Test
    fun `english locale carries no leftover source language text`() {
        val english = load("/lang/en_US.yml")
        val untranslated = english.getKeys(true)
            .filterNot { english.isConfigurationSection(it) }
            .filter { key -> values(english, key).any { CJK.containsMatchIn(it) } }

        assertEquals(emptyList<String>(), untranslated)
    }

    @Test
    fun `menu entries that build a button expose a name`() {
        val chinese = load("/lang/zh_CN.yml")
        val loreKeys = chinese.getKeys(true).filter { it.startsWith("gui.") && it.endsWith(".lore") }

        assertTrue(loreKeys.isNotEmpty())
        for (loreKey in loreKeys) {
            val nameKey = loreKey.removeSuffix(".lore") + ".name"
            assertTrue(chinese.contains(nameKey), "$loreKey has no matching $nameKey")
        }
    }

    private fun values(yaml: YamlConfiguration, key: String): List<String> =
        if (yaml.isList(key)) yaml.getStringList(key) else listOfNotNull(yaml.getString(key))

    private fun keys(resource: String): Set<String> = load(resource).getKeys(true).toSet()

    private fun load(resource: String): YamlConfiguration {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing resource $resource" }
        return InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
    }

    private companion object {
        val CJK = Regex("[\\u4e00-\\u9fff]")
    }
}
