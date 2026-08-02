package org.cubexmc.metro.service

import org.bukkit.Bukkit
import org.cubexmc.metro.Metro
import org.cubexmc.metro.event.LineStatusChangeEvent
import org.cubexmc.metro.manager.LineManager
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.LineStatus

/**
 * Manages line operational status (normal, suspended, maintenance).
 * Provides methods to check, set, and broadcast line status changes.
 */
class LineStatusService(
    private val plugin: Metro,
    private val lineManager: LineManager,
) {
    /**
     * Get the current operational status of a line.
     */
    fun getStatus(line: Line?): LineStatus = line?.getLineStatus() ?: LineStatus.NORMAL

    /**
     * Set the operational status of a line and fire the change event.
     *
     * @param line the line to update
     * @param newStatus the new status
     * @return true if the status was changed
     */
    fun setStatus(line: Line?, newStatus: LineStatus?): Boolean {
        if (line == null || newStatus == null) return false

        val oldStatus = line.getLineStatus()
        if (oldStatus == newStatus) return false

        line.setLineStatus(newStatus)
        Bukkit.getPluginManager().callEvent(LineStatusChangeEvent(line, oldStatus, newStatus))
        lineManager.saveConfig()
        return true
    }

    /**
     * Check if a line is boardable (not suspended).
     */
    fun isBoardable(line: Line?): Boolean = line != null && line.getLineStatus().isBoardable()

    /**
     * Check if a line is suspended.
     */
    fun isSuspended(line: Line?): Boolean = line != null && line.getLineStatus() == LineStatus.SUSPENDED

    /**
     * Check if a line is under maintenance.
     */
    fun isMaintenance(line: Line?): Boolean = line != null && line.getLineStatus() == LineStatus.MAINTENANCE

    /**
     * Get a list of suggested alternative lines for a given line.
     * Uses the line's configured alternative route IDs.
     */
    fun getAlternativeLines(line: Line?): List<Line> {
        if (line == null) return emptyList()

        val altIds = line.alternativeRouteIds
        if (altIds.isEmpty()) return emptyList()

        val alternatives = ArrayList<Line>()
        for (altId in altIds) {
            val altLine = lineManager.getLine(altId)
            if (altLine != null) {
                alternatives.add(altLine)
            }
        }
        return alternatives
    }

    /**
     * Get the suspension message for a line, or a default if none is set.
     */
    fun getSuspensionMessage(line: Line?): String {
        if (line == null) return ""
        val msg = line.suspensionMessage
        return msg ?: ""
    }

    /**
     * Get all lines that are currently suspended or under maintenance.
     */
    fun getNonOperatingLines(): List<Line> {
        val result = ArrayList<Line>()
        for (line in lineManager.getAllLines()) {
            if (line.getLineStatus() != LineStatus.NORMAL) {
                result.add(line)
            }
        }
        return result
    }
}
