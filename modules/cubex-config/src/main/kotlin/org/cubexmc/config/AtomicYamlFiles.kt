package org.cubexmc.config

import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Strict, stateless file operations. Callers own schema validation and runtime state. */
object AtomicYamlFiles {
    @JvmStatic
    @Throws(IOException::class, InvalidConfigurationException::class)
    fun read(file: File): YamlConfiguration {
        val yaml = YamlConfiguration()
        try {
            Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { yaml.load(it) }
        } catch (_: NoSuchFileException) {
            // Existence checks also return false on access errors; only a confirmed missing path is empty.
            return yaml
        }
        return yaml
    }

    /** Stages and validates before replacement. Non-atomic filesystems use a same-directory move. */
    @JvmStatic
    @Throws(IOException::class, InvalidConfigurationException::class)
    fun write(file: File, yaml: YamlConfiguration) {
        val target = file.toPath().toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${file.name}-", ".tmp")
        try {
            yaml.save(temporary.toFile())
            read(temporary.toFile())
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
