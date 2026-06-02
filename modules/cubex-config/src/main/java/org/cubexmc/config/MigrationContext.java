package org.cubexmc.config;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

public interface MigrationContext {
    File file();

    String resourcePath();

    YamlConfiguration yaml();

    void warning(String path, String message);

    void fail(String path, String message);
}
