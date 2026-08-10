package org.cubexmc.regions.integration

import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.cubexmc.regions.RegionsPlugin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LandsRegionSourceTest {
    @Test
    fun `availability requires Lands to be enabled`() {
        val regions = mock(RegionsPlugin::class.java)
        val server = mock(Server::class.java)
        val pluginManager = mock(PluginManager::class.java)
        val lands = mock(Plugin::class.java)
        `when`(regions.config).thenReturn(YamlConfiguration().apply { set("integrations.lands.enabled", true) })
        `when`(regions.server).thenReturn(server)
        `when`(server.pluginManager).thenReturn(pluginManager)
        `when`(pluginManager.getPlugin("Lands")).thenReturn(lands)
        val source = LandsRegionSource(regions)

        `when`(lands.isEnabled).thenReturn(false)
        assertFalse(source.isAvailable())
        `when`(lands.isEnabled).thenReturn(true)
        assertTrue(source.isAvailable())
    }
}
