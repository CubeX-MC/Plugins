package org.cubexmc.config

import org.cubexmc.core.CubexPlugin
import org.cubexmc.core.Reloadable

object ConfigReload {
    @JvmStatic
    fun bukkitConfig(plugin: CubexPlugin): Reloadable = Reloadable { plugin.reloadConfig() }
}
