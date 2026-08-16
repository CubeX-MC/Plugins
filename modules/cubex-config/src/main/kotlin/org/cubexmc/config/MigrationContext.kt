package org.cubexmc.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

interface MigrationContext {
    fun file(): File
    fun resourcePath(): String
    fun yaml(): YamlConfiguration
    fun warning(path: String?, message: String?)
    fun fail(path: String?, message: String?)
}
