package org.cubexmc.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.cubexmc.core.CubexPlugin;

public final class ResourceFiles {

    private final CubexPlugin plugin;

    public ResourceFiles(CubexPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean saveIfMissing(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return false;
        }
        File target = dataFile(resourcePath);
        if (target.exists()) {
            return false;
        }
        if ("config.yml".equals(resourcePath)) {
            plugin.saveDefaultConfig();
        } else {
            plugin.saveResource(resourcePath, false);
        }
        return true;
    }

    public List<String> saveIfMissing(Collection<String> resourcePaths) {
        if (resourcePaths == null || resourcePaths.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> saved = new ArrayList<>();
        for (String resourcePath : resourcePaths) {
            if (saveIfMissing(resourcePath)) {
                saved.add(resourcePath);
            }
        }
        return saved;
    }

    public File dataFile(String resourcePath) {
        return new File(plugin.getDataFolder(), resourcePath);
    }

    public boolean exists(String resourcePath) {
        return dataFile(resourcePath).exists();
    }
}
