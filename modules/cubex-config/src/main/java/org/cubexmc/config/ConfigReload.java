package org.cubexmc.config;

import org.cubexmc.core.CubexPlugin;
import org.cubexmc.core.Reloadable;

public final class ConfigReload {

    private ConfigReload() {
    }

    public static Reloadable bukkitConfig(CubexPlugin plugin) {
        return plugin::reloadConfig;
    }

    public static Reloadable fromRunnable(String name, Runnable action) {
        return action::run;
    }

    public static Reloadable fromThrowing(String name, Reloadable reloadable) {
        return reloadable;
    }
}
