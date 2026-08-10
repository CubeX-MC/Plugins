package org.cubexmc.metro.manager

import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.util.BoundingBox
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.spatial.Octree
import org.cubexmc.metro.spatial.Point3D
import org.cubexmc.metro.spatial.Range3D
import org.cubexmc.metro.update.DataFileUpdater

/**
 * 管理停靠区数据的加载、保存和访问
 */
class StopManager(private val plugin: Metro) {
    private val configFile: File = File(plugin.dataFolder, "stops.yml")
    private lateinit var config: FileConfiguration
    private val stops: MutableMap<String, Stop> = HashMap()
    private val worldStopIndex: MutableMap<String, Octree<Stop>> = HashMap()
    private val lock: ReadWriteLock = ReentrantReadWriteLock()

    @Volatile
    private var isDirty = false

    init {
        loadConfig()
    }

    fun tick() {
    }

    fun saveStops() {
    }

    private fun loadConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("stops.yml", false)
        }

        config = YamlConfiguration.loadConfiguration(configFile)
        lock.writeLock().lock()
        try {
            stops.clear()
            worldStopIndex.clear()

            val stopsSection = config.getConfigurationSection("")
            if (stopsSection != null) {
                for (stopId in stopsSection.getKeys(false)) {
                    if (DataFileUpdater.SCHEMA_VERSION_KEY == stopId) {
                        continue
                    }
                    val stopSection = stopsSection.getConfigurationSection(stopId)
                    if (stopSection != null) {
                        try {
                            val stop = Stop(stopId, stopSection)
                            stops[stopId] = stop
                            indexStop(stop)
                        } catch (exception: RuntimeException) {
                            plugin.logger.warning(
                                "Failed to load stop $stopId from stops.yml: ${exception.message}",
                            )
                        }
                    }
                }
            }
        } finally {
            lock.writeLock().unlock()
        }

        plugin.logger.info("Loaded ${stops.size} stops")
    }

    fun saveConfig() {
        isDirty = true
        plugin.requestMapIntegrationRefresh()
    }

    fun processAsyncSave() {
        if (!isDirty) {
            return
        }
        try {
            val yamlDataFinal = buildSnapshot()
            isDirty = false
            plugin.saveCoordinator.submitSnapshot(configFile.toPath(), yamlDataFinal)
        } catch (exception: Exception) {
            isDirty = true
            plugin.logger.log(Level.SEVERE, "处理停靠区配置时出错", exception)
        }
    }

    fun forceSaveSync() {
        if (!isDirty) {
            return
        }
        try {
            val yamlDataFinal = buildSnapshot()
            isDirty = false
            plugin.saveCoordinator.saveNow(configFile.toPath(), yamlDataFinal)
        } catch (exception: Exception) {
            isDirty = true
            plugin.logger.log(Level.SEVERE, "Could not save stops config", exception)
        }
    }

    fun createStop(
        stopId: String?,
        displayName: String?,
        corner1: Location?,
        corner2: Location?,
        ownerId: UUID?,
    ): Stop? {
        val requiredStopId = stopId ?: throw NullPointerException("stopId")
        lock.writeLock().lock()
        try {
            if (stops.containsKey(requiredStopId)) {
                return null
            }

            val stop = Stop(requiredStopId, if (displayName.isNullOrEmpty()) requiredStopId else displayName)
            stop.owner = ownerId
            if (corner1 != null && corner2 != null) {
                stop.corner1 = corner1
                stop.corner2 = corner2
            }
            stops[requiredStopId] = stop
            indexStop(stop)
            saveConfig()
            return stop
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun setStopCorners(stopId: String?, corner1: Location?, corner2: Location?): Boolean {
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            deindexStop(stop)
            stop.corner1 = corner1
            stop.corner2 = corner2
            indexStop(stop)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun deleteStop(stopId: String?): Boolean {
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            plugin.lineManager.delStopFromAllLines(stop.id)
            deindexStop(stop)
            stops.remove(stop.id)
            config.set(stop.id, null)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun setStopPoint(stopId: String?, location: Location?, yaw: Float): Boolean {
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            deindexStop(stop)
            stop.stopPointLocation = location
            stop.launchYaw = yaw
            indexStop(stop)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun setStopOwner(stopId: String?, ownerId: UUID?): Boolean {
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            stop.owner = ownerId
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun addStopAdmin(stopId: String?, adminId: UUID?): Boolean {
        val changed: Boolean
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            changed = stop.addAdmin(adminId)
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
        return changed
    }

    fun removeStopAdmin(stopId: String?, adminId: UUID?): Boolean {
        val changed: Boolean
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            changed = stop.removeAdmin(adminId)
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
        return changed
    }

    fun allowLineLink(stopId: String?, lineId: String?): Boolean {
        val changed: Boolean
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            changed = stop.allowLine(lineId)
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
        return changed
    }

    fun denyLineLink(stopId: String?, lineId: String?): Boolean {
        val changed: Boolean
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            changed = stop.denyLine(lineId)
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
        return changed
    }

    fun setStopName(stopId: String?, name: String): Boolean {
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            stop.name = name
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun getStop(stopId: String?): Stop? {
        lock.readLock().lock()
        try {
            return stops[stopId]
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getStopContainingLocation(location: Location?): Stop? {
        val world = location?.world ?: return null
        lock.readLock().lock()
        try {
            val octree = worldStopIndex[world.name]
            if (octree != null) {
                val found = octree.firstRange(Point3D(location))
                if (found != null) {
                    return found
                }
            }
            for (stop in stops.values) {
                if (stop.isInStop(location)) {
                    return stop
                }
            }
            return null
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getBestStopContainingLocation(location: Location?, playerYaw: Float): Stop? {
        val world = location?.world ?: return null
        lock.readLock().lock()
        try {
            val candidates = ArrayList<Stop>()
            val octree = worldStopIndex[world.name]
            if (octree != null) {
                candidates.addAll(octree.getAllRanges(Point3D(location)))
            }

            for (stop in stops.values) {
                if (stop.isInStop(location) && !candidates.contains(stop)) {
                    candidates.add(stop)
                }
            }
            if (candidates.isEmpty()) {
                return null
            }

            var bestStop = candidates[0]
            var minDiff = Double.MAX_VALUE
            for (stop in candidates) {
                val stopYaw = stop.launchYaw.toDouble()
                var difference = kotlin.math.abs((stopYaw - playerYaw + 360) % 360)
                difference = kotlin.math.min(difference, 360 - difference)
                if (difference < minDiff) {
                    minDiff = difference
                    bestStop = stop
                }
            }
            return bestStop
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getAllStopIds(): Set<String> {
        lock.readLock().lock()
        try {
            return HashSet(stops.keys)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getAllStops(): List<Stop> {
        lock.readLock().lock()
        try {
            return ArrayList(stops.values)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun addTransferLine(stopId: String?, lineId: String): Boolean {
        val added: Boolean
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            added = stop.addTransferableLine(lineId)
        } finally {
            lock.writeLock().unlock()
        }
        if (added) {
            saveConfig()
        }
        return added
    }

    fun removeTransferLine(stopId: String?, lineId: String): Boolean {
        val removed: Boolean
        lock.writeLock().lock()
        try {
            val stop = stops[stopId] ?: return false
            removed = stop.removeTransferableLine(lineId)
        } finally {
            lock.writeLock().unlock()
        }
        if (removed) {
            saveConfig()
        }
        return removed
    }

    fun getTransferableLines(stopId: String?): List<String> {
        lock.readLock().lock()
        try {
            val stop = stops[stopId] ?: return ArrayList()
            return stop.transferableLines
        } finally {
            lock.readLock().unlock()
        }
    }

    fun reload() {
        loadConfig()
    }

    private fun indexStop(stop: Stop?) {
        if (stop == null) {
            return
        }
        val worldName = stop.worldName
        if (worldName.isNullOrEmpty()) {
            return
        }
        val range = toRange3D(stop.boundingBox) ?: return
        val octree = worldStopIndex.computeIfAbsent(worldName) {
            Octree(
                Range3D(-30000000.0, -64.0, -30000000.0, 30000000.0, 320.0, 30000000.0),
                12,
                8,
            )
        }
        octree.insert(range, stop)
    }

    private fun deindexStop(stop: Stop?) {
        if (stop == null) {
            return
        }
        val worldName = stop.worldName
        if (worldName.isNullOrEmpty()) {
            return
        }
        val range = toRange3D(stop.boundingBox) ?: return
        worldStopIndex[worldName]?.remove(range)
    }

    private fun buildSnapshot(): String {
        val snapshot = YamlConfiguration()
        lock.readLock().lock()
        try {
            if (stops.isNotEmpty() || config.getInt(DataFileUpdater.SCHEMA_VERSION_KEY, 0) > 0) {
                snapshot.set(DataFileUpdater.SCHEMA_VERSION_KEY, DataFileUpdater.CURRENT_SCHEMA_VERSION)
            }

            val stopIds = ArrayList(stops.keys)
            stopIds.sort()
            for (stopId in stopIds) {
                val stop = stops[stopId] ?: continue
                val section: ConfigurationSection = snapshot.createSection(stopId)
                stop.saveToConfig(section)
            }
            return snapshot.saveToString()
        } finally {
            lock.readLock().unlock()
        }
    }

    companion object {
        private fun toRange3D(box: BoundingBox?): Range3D? {
            if (box == null) {
                return null
            }
            return Range3D(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
        }
    }
}
