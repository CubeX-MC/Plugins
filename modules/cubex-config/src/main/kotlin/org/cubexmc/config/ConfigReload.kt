package org.cubexmc.config

import org.cubexmc.core.CubexPlugin
import org.cubexmc.core.Reloadable

public object ConfigReload {
    @JvmStatic
    public fun bukkitConfig(plugin: CubexPlugin): Reloadable = Reloadable { plugin.reloadConfig() }
}
