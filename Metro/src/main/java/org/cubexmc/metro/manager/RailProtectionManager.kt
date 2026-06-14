package org.cubexmc.metro.manager

import java.util.EnumSet
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.RoutePoint
import org.cubexmc.metro.util.MetroConstants
import org.cubexmc.metro.util.OwnershipUtil

/**
 * Builds an in-memory rail protection index from recorded route points.
 */
class RailProtectionManager(private val plugin: Metro) : Listener {
    private val lineToBlocks: MutableMap<String, MutableSet<BlockKey>> = HashMap()
    private val blockToLines: MutableMap<BlockKey, MutableSet<String>> = HashMap()
    private val lineToStats: MutableMap<String, ProtectionIndexStats> = HashMap()

    @Synchronized
    fun rebuildAll() {
        lineToBlocks.clear()
        blockToLines.clear()
        lineToStats.clear()
        for (line in plugin.lineManager.getAllLines()) {
            rebuildLineLocked(line)
        }
    }

    @Synchronized
    fun rebuildLine(lineId: String) {
        removeLineLocked(lineId)
        val line = plugin.lineManager.getLine(lineId)
        rebuildLineLocked(line)
    }

    @Synchronized
    fun getProtectedBlockCount(lineId: String): Int = lineToBlocks[lineId]?.size ?: 0

    @Synchronized
    fun getProtectionIndexStats(lineId: String): ProtectionIndexStats =
        lineToStats[lineId] ?: ProtectionIndexStats.empty()

    private fun rebuildLineLocked(line: Line?) {
        if (line == null || !line.isRailProtected) {
            return
        }
        val builder = ProtectionIndexBuilder(line.worldName)
        val blocks = collectRailBlocks(line.routePoints, builder)
        val stats = builder.build(blocks.size)
        lineToStats[line.id] = stats
        if (blocks.isEmpty()) {
            return
        }
        lineToBlocks[line.id] = blocks.toMutableSet()
        for (block in blocks) {
            blockToLines.computeIfAbsent(block) { HashSet() }.add(line.id)
        }
    }

    private fun removeLineLocked(lineId: String) {
        lineToStats.remove(lineId)
        val oldBlocks = lineToBlocks.remove(lineId) ?: return
        for (block in oldBlocks) {
            val lineIds = blockToLines[block] ?: continue
            lineIds.remove(lineId)
            if (lineIds.isEmpty()) {
                blockToLines.remove(block)
            }
        }
    }

    private fun collectRailBlocks(routePoints: List<RoutePoint>?, builder: ProtectionIndexBuilder): Set<BlockKey> {
        if (routePoints == null || routePoints.isEmpty()) {
            return emptySet()
        }
        val blocks = HashSet<BlockKey>()
        val points = ArrayList(routePoints)
        for (index in points.indices) {
            addNearestRail(blocks, points[index], builder)
            if (index + 1 < points.size) {
                interpolateSegment(blocks, points[index], points[index + 1], builder)
            }
        }
        return blocks
    }

    private fun interpolateSegment(blocks: MutableSet<BlockKey>, from: RoutePoint?, to: RoutePoint?, builder: ProtectionIndexBuilder) {
        if (from == null || to == null || from.worldName() != to.worldName()) {
            return
        }
        val dx = to.x() - from.x()
        val dy = to.y() - from.y()
        val dz = to.z() - from.z()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val samples = kotlin.math.max(1, kotlin.math.ceil(distance / INTERPOLATION_STEP).toInt())
        for (index in 1 until samples) {
            val ratio = index.toDouble() / samples
            addNearestRail(
                blocks,
                RoutePoint(
                    from.worldName(),
                    from.x() + dx * ratio,
                    from.y() + dy * ratio,
                    from.z() + dz * ratio,
                ),
                builder,
            )
        }
    }

    private fun addNearestRail(blocks: MutableSet<BlockKey>, point: RoutePoint?, builder: ProtectionIndexBuilder) {
        builder.sample(point)
        if (point == null) {
            return
        }
        if (builder.isWorldMismatch(point)) {
            builder.skippedWorldMismatch()
            return
        }
        val world = Bukkit.getWorld(point.worldName())
        if (world == null) {
            builder.skippedMissingWorld()
            return
        }
        val nearestRail = findNearestRail(point)
        if (nearestRail != null) {
            blocks.add(nearestRail)
        } else {
            builder.skippedNoRail()
        }
    }

    private fun findNearestRail(point: RoutePoint): BlockKey? {
        val world: World = Bukkit.getWorld(point.worldName()) ?: return null

        val baseX = kotlin.math.floor(point.x()).toInt()
        val baseY = kotlin.math.floor(point.y()).toInt()
        val baseZ = kotlin.math.floor(point.z()).toInt()
        var best: BlockKey? = null
        var bestDistance = Double.MAX_VALUE

        for (dy in intArrayOf(0, -1)) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val x = baseX + dx
                    val y = baseY + dy
                    val z = baseZ + dz
                    val block = world.getBlockAt(x, y, z)
                    if (!isRail(block)) {
                        continue
                    }
                    val distance = distanceSquaredToBlockCenter(point, x, y, z)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = BlockKey(world.name, x, y, z)
                    }
                }
            }
        }
        return best
    }

    private fun distanceSquaredToBlockCenter(point: RoutePoint, x: Int, y: Int, z: Int): Double {
        val dx = point.x() - (x + 0.5)
        val dy = point.y() - (y + 0.5)
        val dz = point.z() - (z + 0.5)
        return dx * dx + dy * dy + dz * dz
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block

        if (isMetroPassengerBreakingRail(player, block)) {
            event.isCancelled = true
            player.sendMessage(plugin.languageManager.getMessage("protection.passenger_break_denied"))
            return
        }

        val protectedLineIds = getProtectedLines(block)
        if (protectedLineIds.isEmpty()) {
            return
        }
        if (canBreakProtectedRail(player, protectedLineIds)) {
            return
        }

        event.isCancelled = true
        player.sendMessage(plugin.languageManager.getMessage("protection.rail_break_denied"))
    }

    private fun isMetroPassengerBreakingRail(player: Player, block: Block): Boolean {
        if (!plugin.configFacade.isSafeModePassengerRailBreakProtection() || !isRail(block)) {
            return false
        }
        val minecart = player.vehicle as? Minecart ?: return false
        val key = MetroConstants.getMinecartKey() ?: return false
        return minecart.persistentDataContainer.has(key, PersistentDataType.BYTE)
    }

    @Synchronized
    private fun getProtectedLines(block: Block): Set<String> {
        if (!isRail(block)) {
            return emptySet()
        }
        val lineIds = blockToLines[BlockKey.fromBlock(block)]
        return if (lineIds == null) emptySet() else HashSet(lineIds)
    }

    private fun canBreakProtectedRail(player: Player, protectedLineIds: Set<String>): Boolean {
        if (OwnershipUtil.hasAdminBypass(player)) {
            return true
        }
        for (lineId in protectedLineIds) {
            val line = plugin.lineManager.getLine(lineId)
            if (line != null && !OwnershipUtil.canManageLine(player, line)) {
                return false
            }
        }
        return true
    }

    private fun isRail(block: Block?): Boolean = block != null && RAIL_MATERIALS.contains(block.type)

    data class ProtectionIndexStats(
        private val sampledPoints: Int,
        private val indexedBlocks: Int,
        private val skippedWorldMismatch: Int,
        private val skippedMissingWorld: Int,
        private val skippedNoRail: Int,
    ) {
        fun sampledPoints(): Int = sampledPoints

        fun indexedBlocks(): Int = indexedBlocks

        fun skippedWorldMismatch(): Int = skippedWorldMismatch

        fun skippedMissingWorld(): Int = skippedMissingWorld

        fun skippedNoRail(): Int = skippedNoRail

        fun skippedTotal(): Int = skippedWorldMismatch + skippedMissingWorld + skippedNoRail

        fun hasWarnings(): Boolean = skippedTotal() > 0

        companion object {
            @JvmStatic
            fun empty(): ProtectionIndexStats = ProtectionIndexStats(0, 0, 0, 0, 0)
        }
    }

    private class ProtectionIndexBuilder(private val lineWorldName: String?) {
        private var sampledPoints = 0
        private var skippedWorldMismatch = 0
        private var skippedMissingWorld = 0
        private var skippedNoRail = 0

        fun sample(point: RoutePoint?) {
            if (point != null) {
                sampledPoints++
            }
        }

        fun isWorldMismatch(point: RoutePoint?): Boolean =
            point != null &&
                lineWorldName != null &&
                lineWorldName.isNotBlank() &&
                lineWorldName != point.worldName()

        fun skippedWorldMismatch() {
            skippedWorldMismatch++
        }

        fun skippedMissingWorld() {
            skippedMissingWorld++
        }

        fun skippedNoRail() {
            skippedNoRail++
        }

        fun build(indexedBlocks: Int): ProtectionIndexStats =
            ProtectionIndexStats(
                sampledPoints,
                indexedBlocks,
                skippedWorldMismatch,
                skippedMissingWorld,
                skippedNoRail,
            )
    }

    private data class BlockKey(
        private val worldName: String,
        private val x: Int,
        private val y: Int,
        private val z: Int,
    ) {
        companion object {
            fun fromBlock(block: Block): BlockKey =
                BlockKey(block.world.name, block.x, block.y, block.z)
        }
    }

    companion object {
        private const val INTERPOLATION_STEP = 0.5
        private val RAIL_MATERIALS: Set<Material> = EnumSet.of(
            Material.RAIL,
            Material.POWERED_RAIL,
            Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL,
        )
    }
}
