package org.cubexmc.metro.placeholder

import java.util.Locale
import org.bukkit.OfflinePlayer
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.service.LineService
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.util.SchedulerUtil

class RailwayPlaceholders(
    private val plugin: Metro,
) : NullablePlaceholderExpansion() {
    override fun getAuthor(): String {
        val authors = plugin.description.authors
        return if (authors.isEmpty()) "railway" else authors.joinToString(", ")
    }

    override fun getIdentifier(): String = "railway"

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String?): String {
        if (params.isNullOrEmpty()) {
            return ""
        }
        val parts = params.split('_').dropLastWhile { it.isEmpty() }.toTypedArray()
        if (parts.isEmpty()) {
            return ""
        }
        return when (parts[0].lowercase(Locale.getDefault())) {
            "eta" -> {
                // %railway_eta_line_l1_stop_s3%
                val lineId = findValue(parts, "line")
                val stopId = findValue(parts, "stop")
                if (lineId == null || stopId == null) {
                    ""
                } else {
                    val etaSeconds = estimateEtaSeconds(lineId, stopId)
                    if (etaSeconds < 0) "--:--" else formatSeconds(etaSeconds)
                }
            }

            "next", "nextstop" -> {
                // %railway_next_line_l1_from_s3%
                val lineId = findValue(parts, "line")
                val fromId = findValue(parts, "from")
                if (lineId == null || fromId == null) "" else nextStopName(lineId, fromId)
            }

            else -> ""
        }
    }

    private fun findValue(parts: Array<String>, key: String): String? {
        for (index in 0 until parts.size - 1) {
            if (key.equals(parts[index], ignoreCase = true)) {
                return parts[index + 1]
            }
        }
        // Also support simple pattern eta_l1_s3
        if (key == "line" && parts.size >= 3) {
            return parts[1]
        }
        if (key == "stop" && parts.size >= 3) {
            return parts[2]
        }
        if (key == "from" && parts.size >= 3) {
            return parts[2]
        }
        return null
    }

    private fun estimateEtaSeconds(lineId: String, stopId: String): Int {
        val service: LineService = plugin.lineServiceManager.getService(lineId) ?: return -1
        var best = Int.MAX_VALUE
        val now = SchedulerUtil.getCurrentTick()
        for (train: TrainInstance in service.activeTrains) {
            val eta = train.estimateEtaSecondsToStop(stopId, now, plugin.travelTimeEstimator)
            if (eta != Double.POSITIVE_INFINITY) {
                val seconds = Math.round(eta).toInt()
                if (seconds < best) {
                    best = seconds
                }
            }
        }
        if (best != Int.MAX_VALUE) {
            return best
        }
        return plugin.lineServiceManager.estimateNextEtaSeconds(lineId, stopId)
    }

    private fun nextStopName(lineId: String, fromId: String): String {
        val line: Line = plugin.lineManager.getLine(lineId) ?: return ""
        val stops: List<String>? = line.orderedStopIds
        if (stops.isNullOrEmpty()) {
            return ""
        }
        val index = stops.indexOf(fromId)
        if (index < 0) {
            return ""
        }
        val service = plugin.lineServiceManager.getService(lineId)
        val loop = service != null && service.isLoopLine
        var nextIndex = index + 1
        if (nextIndex >= stops.size) {
            if (loop) {
                nextIndex = 0
            } else {
                return ""
            }
        }
        val nextId = stops[nextIndex]
        val stop: Stop? = plugin.stopManager.getStop(nextId)
        val stopName: String? = stop?.name
        return if (!stopName.isNullOrEmpty()) stopName else nextId
    }

    private fun formatSeconds(seconds: Int): String {
        val clampedSeconds = maxOf(0, seconds)
        val minutes = clampedSeconds / 60
        val remainingSeconds = clampedSeconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
}
