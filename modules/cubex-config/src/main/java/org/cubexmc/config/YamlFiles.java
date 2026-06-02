package org.cubexmc.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;

public final class YamlFiles {

    private final CubexPlugin plugin;

    public YamlFiles(CubexPlugin plugin) {
        this.plugin = plugin;
    }

    public YamlConfiguration loadDataFile(String resourcePath) {
        return loadDataFile(new File(plugin.getDataFolder(), resourcePath));
    }

    public YamlConfiguration loadDataFile(File file) {
        return YamlConfiguration.loadConfiguration(file);
    }

    public YamlConfiguration loadResource(String resourcePath, Charset charset) {
        InputStream inputStream = plugin.getResource(resourcePath);
        if (inputStream == null) {
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(inputStream, charset)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load YAML resource " + resourcePath + ": " + ex.getMessage());
            return new YamlConfiguration();
        }
    }

    public YamlConfiguration loadResourceUtf8(String resourcePath) {
        return loadResource(resourcePath, StandardCharsets.UTF_8);
    }
}
