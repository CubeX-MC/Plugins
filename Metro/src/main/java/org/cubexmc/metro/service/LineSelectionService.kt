package org.cubexmc.metro.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import org.bukkit.Location
import org.bukkit.entity.Player
import org.cubexmc.metro.manager.LineManager
import org.cubexmc.metro.manager.StopManager
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop

/**
 * Resolves which lines are boardable at a given stop, handles per-player
 * line-choice remembering, and selects the default line by yaw alignment.
 *
 * A line is "boardable" when the stop is the line's current calling point
 * (not a terminal), the next stop has a stop-point configured, and the
 * stop's linked-line set (if non-empty) includes the line.
 */
class LineSelectionService(
    private val lineManager: LineManager,
    private val stopManager: StopManager,
) {
    private val recentChoices: MutableMap<UUID, MutableMap<String, String>> = ConcurrentHashMap()

    /**
     * Returns the lines that can be boarded from the given stop.
     * Results are sorted by line ID.
     *
     * @param stop the stop to query
     * @return sorted, non-null list of boardable lines
     */
    fun getBoardableLines(stop: Stop?): List<Line> {
        if (stop == null) {
            return emptyList()
        }

        val lines = ArrayList<Line>()
        for (line in lineManager.getLinesForStop(stop.id)) {
            if (isBoardable(line, stop)) {
                lines.add(line)
            }
        }
        lines.sortBy { line -> line.id }
        return lines
    }

    /**
     * Returns lines that terminate at the given stop (no next stop).
     * Results are sorted by line ID.
     *
     * @param stop the stop to query
     * @return sorted, non-null list of terminal lines
     */
    fun getTerminalLines(stop: Stop?): List<Line> {
        if (stop == null) {
            return emptyList()
        }

        val lines = ArrayList<Line>()
        for (line in lineManager.getLinesForStop(stop.id)) {
            if (isTerminalLine(line, stop)) {
                lines.add(line)
            }
        }
        lines.sortBy { line -> line.id }
        return lines
    }

    /**
     * Resolves the default line for boarding, preferring a previously
     * remembered choice, then the line whose launch yaw best matches the
     * player's current yaw.
     *
     * @param player the boarding player
     * @param stop the stop to board from
     * @param clickedLocation the block the player clicked
     * @return the best-matching boardable line, or `null` if none
     */
    fun resolveDefaultLine(player: Player?, stop: Stop?, clickedLocation: Location?): Line? {
        val lines = ArrayList(getBoardableLines(stop))
        if (lines.isEmpty()) {
            return null
        }
        lines.sortWith(lineComparator(player, stop, clickedLocation))
        return lines[0]
    }

    /**
     * Whether the player must choose a line explicitly (multiple boardable
     * lines and no remembered choice).
     *
     * @param player the boarding player
     * @param stop the stop to board from
     * @return `true` if a choice GUI should be shown
     */
    fun requiresChoice(player: Player?, stop: Stop?): Boolean {
        val lines = getBoardableLines(stop)
        if (lines.size <= 1) {
            return false
        }
        val rememberedLineId = getRememberedLineId(player, stop)
        return rememberedLineId == null || lines.none { line -> line.id == rememberedLineId }
    }

    /**
     * Records the player's choice of line for a given stop so the same
     * line is auto-selected next time.
     *
     * @param player the boarding player
     * @param stopId the stop ID
     * @param lineId the chosen line ID
     */
    fun rememberChoice(player: Player?, stopId: String?, lineId: String?) {
        if (player == null || stopId == null || stopId.isEmpty() || lineId == null || lineId.isEmpty()) {
            return
        }
        recentChoices.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[stopId] = lineId
    }

    private fun isBoardable(line: Line?, stop: Stop): Boolean {
        if (!isUsableLineAtStop(line, stop)) {
            return false
        }

        val nextStopId = line?.getNextStopId(stop.id)
        if (nextStopId == null || nextStopId.isEmpty()) {
            return false
        }

        val nextStop = stopManager.getStop(nextStopId)
        if (nextStop == null || nextStop.stopPointLocation == null) {
            return false
        }

        return true
    }

    private fun isTerminalLine(line: Line?, stop: Stop): Boolean {
        if (!isUsableLineAtStop(line, stop)) {
            return false
        }

        val nextStopId = line?.getNextStopId(stop.id)
        return nextStopId == null || nextStopId.isEmpty()
    }

    private fun isUsableLineAtStop(line: Line?, stop: Stop?): Boolean {
        if (line == null || stop == null || !line.containsStop(stop.id) || stop.stopPointLocation == null) {
            return false
        }

        val linkedLineIds = stop.linkedLineIds
        return linkedLineIds.isEmpty() || linkedLineIds.contains(line.id)
    }

    private fun lineComparator(player: Player?, stop: Stop?, clickedLocation: Location?): Comparator<Line> {
        val rememberedLineId = getRememberedLineId(player, stop)
        val playerYaw = resolveYaw(player, clickedLocation)
        return Comparator
            .comparing<Line, Boolean> { line -> line.id != rememberedLineId }
            .thenComparingDouble { yawDifference(playerYaw, stop?.launchYaw ?: 0.0f) }
            .thenComparing { line -> line.id }
    }

    private fun getRememberedLineId(player: Player?, stop: Stop?): String? {
        if (player == null || stop == null) {
            return null
        }
        val choices = recentChoices[player.uniqueId]
        return choices?.get(stop.id)
    }

    private fun resolveYaw(player: Player?, clickedLocation: Location?): Float {
        if (player != null && player.location != null) {
            return player.location.yaw
        }
        return clickedLocation?.yaw ?: 0.0f
    }

    private fun yawDifference(a: Float, b: Float): Double {
        val diff = abs((a - b + 360.0) % 360.0)
        return min(diff, 360.0 - diff)
    }
}
