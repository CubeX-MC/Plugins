package org.cubexmc.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

public interface MigrationContext {
    public fun file(): File
    public fun resourcePath(): String
    public fun yaml(): YamlConfiguration
    public fun warning(path: String?, message: String?)
    public fun fail(path: String?, message: String?)
}
