package org.cubexmc.config;

import org.cubexmc.core.CubexPlugin;
import org.cubexmc.core.Reloadable;

/** Ready-made {@link Reloadable} stages for {@link ReloadChain}. */
public final class ConfigReload {

    private ConfigReload() {
    }

    /** Re-reads {@code config.yml} through Bukkit. */
    public static Reloadable bukkitConfig(CubexPlugin plugin) {
        return plugin::reloadConfig;
    }
}
