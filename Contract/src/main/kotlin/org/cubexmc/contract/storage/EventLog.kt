package org.cubexmc.contract.storage

import org.cubexmc.contract.ContractPlugin
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class EventLog(private val plugin: ContractPlugin) {
    private val file: File = File(plugin.dataFolder, "events.log")

    fun append(contractId: String, type: String, detail: String?) {
        try {
            appendRequired(contractId, type, detail)
        } catch (ex: IOException) {
            plugin.log().warn("Failed to append event log: ${ex.message}")
        }
    }

    /** Financial callers can fail closed when their write-ahead audit cannot be written. */
    @Throws(IOException::class)
    @Synchronized
    fun appendRequired(contractId: String, type: String, detail: String?) {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }
        val line = FORMAT.format(Instant.now()) +
            " | " + contractId +
            " | " + type +
            " | " + sanitize(detail) +
            System.lineSeparator()
        FileWriter(file, true).use { writer -> writer.write(line) }
    }

    private fun sanitize(detail: String?): String {
        if (detail == null) {
            return ""
        }
        return detail.replace("\r", " ").replace("\n", " ").replace("|", "/")
    }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault())
    }
}
