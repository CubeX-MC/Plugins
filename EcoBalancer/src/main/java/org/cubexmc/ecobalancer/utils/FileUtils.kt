package org.cubexmc.ecobalancer.utils

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.FileHandler
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.util.zip.GZIPOutputStream

object FileUtils {
    @JvmStatic
    fun initializeLogger(plugin: JavaPlugin, fileLogger: Logger): FileHandler? {
        val logDir = File(plugin.dataFolder.toString() + File.separator + "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val lockFile = File(plugin.dataFolder.toString() + File.separator + "logs" + File.separator + "latest.log.lck")
        if (lockFile.exists()) {
            lockFile.delete()
        }

        val existingLogFile = File(plugin.dataFolder.toString() + File.separator + "logs" + File.separator + "latest.log")
        if (existingLogFile.exists()) {
            compressLogFile(existingLogFile, plugin.logger)
        }

        return try {
            val fileHandler = FileHandler(plugin.dataFolder.toString() + File.separator + "logs" + File.separator + "latest.log", true)
            fileHandler.formatter = SimpleFormatter()
            fileLogger.addHandler(fileHandler)
            fileLogger.useParentHandlers = false
            fileHandler
        } catch (exception: IOException) {
            plugin.logger.severe("无法创建日志文件处理器: ${exception.message}")
            exception.printStackTrace()
            null
        }
    }

    @JvmStatic
    fun compressLogFile(logFile: File, logger: Logger) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd-HHmm")
        val timestamp = dateFormat.format(Date(logFile.lastModified()))
        val renamedLogFile = File(logFile.parent, "$timestamp.log")

        if (!logFile.renameTo(renamedLogFile)) {
            logger.severe("无法重命名日志文件")
            return
        }

        val compressedFile = File(renamedLogFile.parent, renamedLogFile.name + ".gz")
        try {
            GZIPOutputStream(FileOutputStream(compressedFile)).use { gzos ->
                Files.copy(renamedLogFile.toPath(), gzos)
            }
        } catch (exception: IOException) {
            logger.severe("无法压缩日志文件: ${exception.message}")
        }

        if (!renamedLogFile.delete()) {
            logger.severe("压缩后无法删除原始日志文件")
        }
    }

    @JvmStatic
    fun cleanupLogger(fileHandler: FileHandler?, fileLogger: Logger, plugin: JavaPlugin) {
        if (fileHandler != null) {
            fileHandler.flush()
            fileLogger.removeHandler(fileHandler)
            fileHandler.close()
        }

        val logFile = File(plugin.dataFolder.toString() + File.separator + "logs" + File.separator + "latest.log")
        if (logFile.exists()) {
            compressLogFile(logFile, plugin.logger)
        }
    }

    @JvmStatic
    fun ensureDirectoryExists(directoryPath: String): Boolean {
        val directory = File(directoryPath)
        if (!directory.exists()) {
            return directory.mkdirs()
        }
        return true
    }
}
