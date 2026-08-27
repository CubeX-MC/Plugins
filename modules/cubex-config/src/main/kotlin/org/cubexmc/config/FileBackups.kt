package org.cubexmc.config

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Unique destinations prevent successive upgrade steps from overwriting an earlier backup. */
object FileBackups {
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    @JvmStatic
    @Throws(IOException::class)
    fun copyUnique(source: File, directory: File): File {
        Files.createDirectories(directory.toPath())
        val prefix = "${source.nameWithoutExtension}-${LocalDateTime.now().format(timestamp)}-"
        val suffix = source.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        val target = Files.createTempFile(directory.toPath(), prefix, suffix)
        try {
            // The only replaced file is the empty destination reserved by this invocation.
            Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            return target.toFile()
        } catch (failure: IOException) {
            Files.deleteIfExists(target)
            throw failure
        }
    }
}
