package org.cubexmc.metro.manager

import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.metro.Metro
import org.cubexmc.metro.control.TrainControlMode
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.LineStatus
import org.cubexmc.metro.model.PriceRule
import org.cubexmc.metro.model.RoutePoint
import org.cubexmc.metro.update.DataFileUpdater

/**
 * 线路管理器，负责线路数据的加载、保存和操作
 */
class LineManager(private val plugin: Metro) {
    private val configFile: File = File(plugin.dataFolder, "lines.yml")
    private lateinit var config: FileConfiguration
    private val lines: MutableMap<String, Line> = HashMap()
    private val stopToLinesIndex: MutableMap<String, MutableSet<String>> = HashMap()
    private val lock: ReadWriteLock = ReentrantReadWriteLock()

    @Volatile
    private var isDirty = false

    init {
        loadConfig()
    }

    private fun loadConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("lines.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(configFile)
        loadLines()
    }

    private fun loadLines() {
        lock.writeLock().lock()
        try {
            lines.clear()
            stopToLinesIndex.clear()
            val linesSection = config.getConfigurationSection("")
            if (linesSection != null) {
                for (lineId in linesSection.getKeys(false)) {
                    if (DataFileUpdater.SCHEMA_VERSION_KEY == lineId) {
                        continue
                    }
                    var name = config.getString("$lineId.name")
                    if (name.isNullOrBlank()) {
                        plugin.logger.warning("Line $lineId is missing name, using line id as fallback.")
                        name = lineId
                    }
                    val line = Line(lineId, name)

                    for (stopId in config.getStringList("$lineId.ordered_stop_ids")) {
                        line.addStop(stopId, -1)
                    }
                    for (portalId in config.getStringList("$lineId.portal_ids")) {
                        line.addPortal(portalId)
                    }

                    val routePointStrings = config.getStringList("$lineId.route_points")
                    if (routePointStrings.isNotEmpty()) {
                        val routePoints = ArrayList<RoutePoint>()
                        for (routePointString in routePointStrings) {
                            val routePoint = RoutePoint.fromConfigString(routePointString)
                            if (routePoint != null) {
                                routePoints.add(routePoint)
                            }
                        }
                        line.setRoutePoints(routePoints)
                    }
                    line.routeRecordedAtEpochMillis = config.getLong("$lineId.route_recorded_at", 0L)
                    line.routeRecordedBy = readUuid(lineId, "route_recorded_by")
                    line.routeRecordedCartId = readUuid(lineId, "route_recorded_cart")

                    config.getString("$lineId.color")?.let { color -> line.color = color }
                    config.getString("$lineId.terminus_name")?.let { terminus -> line.terminusName = terminus }

                    val maxSpeed = config.getDouble("$lineId.max_speed", -1.0)
                    if (maxSpeed >= 0) {
                        line.setMaxSpeed(maxSpeed)
                    }
                    line.ticketPrice = config.getDouble("$lineId.ticket_price", 0.0)

                    val serviceSection = config.getConfigurationSection("$lineId.service")
                    if (serviceSection != null) {
                        line.isServiceEnabled = serviceSection.getBoolean("enabled", false)
                        line.headwaySeconds = serviceSection.getInt("headway_seconds", Line.DEFAULT_HEADWAY_SECONDS)
                        line.dwellTicks = serviceSection.getInt("dwell_ticks", Line.DEFAULT_DWELL_TICKS)
                        line.trainCars = serviceSection.getInt("train_cars", Line.DEFAULT_TRAIN_CARS)
                        line.controlMode = TrainControlMode.from(serviceSection.getString("control_mode"), null)
                    }

                    val entityType = config.getString("$lineId.entity_type")
                    if (!entityType.isNullOrBlank()) {
                        line.setEntityType(entityType)
                    }

                    loadPriceRule(lineId, line)

                    config.getString("$lineId.line_status")?.let { status ->
                        line.setLineStatus(LineStatus.fromConfig(status))
                    }
                    val alternativeRoutes = config.getStringList("$lineId.alternative_routes")
                    if (alternativeRoutes.isNotEmpty()) {
                        line.setAlternativeRouteIds(alternativeRoutes)
                    }
                    val suspensionMessage = config.getString("$lineId.suspension_message")
                    if (!suspensionMessage.isNullOrEmpty()) {
                        line.suspensionMessage = suspensionMessage
                    }

                    line.isRailProtected = config.getBoolean("$lineId.rail_protected", false)
                    line.owner = readUuid(lineId, "owner")
                    loadAdmins(lineId, line)

                    val worldName = config.getString("$lineId.world")
                    if (!worldName.isNullOrEmpty()) {
                        line.worldName = worldName
                    }

                    lines[lineId] = line
                    indexLineStops(line)
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun loadPriceRule(lineId: String, line: Line) {
        if (!config.contains("$lineId.price_rule")) {
            return
        }
        val priceSection = config.getConfigurationSection("$lineId.price_rule") ?: return
        val priceMap = priceSection.getValues(true)
        val flattened: MutableMap<String, Any> = HashMap()
        for ((key, value) in priceMap) {
            val topKey = if (key.contains(".")) key.substring(0, key.indexOf('.')) else key
            if (!flattened.containsKey(topKey)) {
                if (value is ConfigurationSection) {
                    flattened[topKey] = value.getValues(false)
                } else if (value != null) {
                    flattened[topKey] = value
                }
            }
        }
        if (priceMap.containsKey("time_discounts")) {
            val rawList = config.getList("$lineId.price_rule.time_discounts")
            if (rawList != null) {
                val discountList = ArrayList<Map<String, Any>>()
                for (item in rawList) {
                    if (item is Map<*, *>) {
                        val discountMap = HashMap<String, Any>()
                        for ((key, value) in item) {
                            if (key is String && value != null) {
                                discountMap[key] = value
                            }
                        }
                        discountList.add(discountMap)
                    }
                }
                flattened["time_discounts"] = discountList
            }
        }
        try {
            line.priceRule = PriceRule.deserialize(flattened)
        } catch (exception: Exception) {
            plugin.logger.log(Level.WARNING, "Failed to deserialize price_rule for line $lineId", exception)
        }
    }

    private fun loadAdmins(lineId: String, line: Line) {
        val adminStrings = config.getStringList("$lineId.admins")
        if (adminStrings.isEmpty()) {
            return
        }
        val adminIds = HashSet<UUID>()
        for (adminString in adminStrings) {
            try {
                adminIds.add(UUID.fromString(adminString))
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("Invalid admin UUID in lines.yml for line $lineId: $adminString")
            }
        }
        line.setAdmins(adminIds)
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
            plugin.logger.log(Level.SEVERE, "处理线路配置时出错", exception)
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
            plugin.logger.log(Level.SEVERE, "无法同步保存线路配置", exception)
        }
    }

    fun tick() {
    }

    fun saveLines() {
        forceSaveSync()
    }

    fun saveStops() {
    }

    fun getLine(lineId: String?): Line? {
        lock.readLock().lock()
        try {
            return lines[lineId]
        } finally {
            lock.readLock().unlock()
        }
    }

    fun createLine(lineId: String?, name: String?, ownerId: UUID?): Boolean {
        val requiredLineId = lineId ?: return false
        lock.writeLock().lock()
        try {
            if (lines.containsKey(requiredLineId)) {
                return false
            }
            val line = Line(requiredLineId, name ?: requiredLineId)
            line.owner = ownerId
            lines[requiredLineId] = line
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        rebuildRailProtection(requiredLineId)
        return true
    }

    fun deleteLine(lineId: String?): Boolean {
        lock.writeLock().lock()
        val removed: Line
        try {
            removed = lines.remove(lineId) ?: return false
            deindexLineStops(removed)
            config.set(removed.id, null)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        rebuildRailProtection(removed.id)
        return true
    }

    fun addStopToLine(lineId: String?, stopId: String, index: Int): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            deindexLineStops(line)
            line.addStop(stopId, index)
            indexLineStops(line)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun setLineWorldName(lineId: String?, worldName: String?): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            line.worldName = worldName
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun delStopFromLine(lineId: String?, stopId: String): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            deindexLineStops(line)
            line.delStop(stopId)
            indexLineStops(line)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun delStopFromAllLines(stopId: String) {
        lock.writeLock().lock()
        try {
            for (line in lines.values) {
                if (line.containsStop(stopId)) {
                    deindexLineStops(line)
                    line.delStop(stopId)
                    indexLineStops(line)
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
    }

    fun getAllLines(): List<Line> {
        lock.readLock().lock()
        try {
            return ArrayList(lines.values)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getLinesForStop(stopId: String?): List<Line> {
        lock.readLock().lock()
        try {
            val lineIds = stopToLinesIndex[stopId]
            if (lineIds.isNullOrEmpty()) {
                return ArrayList()
            }
            val result = ArrayList<Line>()
            for (lineId in lineIds) {
                lines[lineId]?.let { line -> result.add(line) }
            }
            return result
        } finally {
            lock.readLock().unlock()
        }
    }

    fun reload() {
        loadConfig()
        plugin.railProtectionManager?.rebuildAll()
    }

    fun setLineColor(lineId: String?, color: String?): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            line.color = color ?: throw NullPointerException("color")
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun setLineTerminusName(lineId: String?, terminusName: String?): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            line.terminusName = terminusName ?: throw NullPointerException("terminusName")
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun setLineName(lineId: String?, name: String?): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            line.name = name ?: throw NullPointerException("name")
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun setLineMaxSpeed(lineId: String?, maxSpeed: Double?): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            line.setMaxSpeed(maxSpeed)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun setLineTicketPrice(lineId: String?, ticketPrice: Double): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            line.ticketPrice = ticketPrice
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun setLineServiceEnabled(lineId: String?, enabled: Boolean): Boolean =
        updateLine(lineId) { line -> line.isServiceEnabled = enabled }

    fun setLineHeadwaySeconds(lineId: String?, seconds: Int): Boolean =
        updateLine(lineId) { line -> line.headwaySeconds = seconds }

    fun setLineDwellTicks(lineId: String?, ticks: Int): Boolean =
        updateLine(lineId) { line -> line.dwellTicks = ticks }

    fun setLineTrainCars(lineId: String?, trainCars: Int): Boolean =
        updateLine(lineId) { line -> line.trainCars = trainCars }

    fun setLineControlMode(lineId: String?, controlMode: TrainControlMode?): Boolean =
        updateLine(lineId) { line -> line.controlMode = controlMode }

    fun setLineEntityType(lineId: String?, entityType: String?): Boolean =
        updateLine(lineId) { line -> line.setEntityType(entityType) }

    private fun updateLine(lineId: String?, update: (Line) -> Unit): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            update(line)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun addPortalToLine(lineId: String?, portalId: String?): Boolean {
        val changed: Boolean
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            changed = line.addPortal(portalId)
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
        return changed
    }

    fun delPortalFromLine(lineId: String?, portalId: String?): Boolean {
        val changed: Boolean
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            changed = line.delPortal(portalId)
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
        return changed
    }

    fun delPortalFromAllLines(portalId: String?) {
        var changed = false
        lock.writeLock().lock()
        try {
            for (line in lines.values) {
                if (line.delPortal(portalId)) {
                    changed = true
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
    }

    fun setLineRoutePoints(lineId: String?, routePoints: List<RoutePoint>?): Boolean =
        setLineRoutePoints(lineId, routePoints, null, null, null)

    fun setLineRoutePoints(
        lineId: String?,
        routePoints: List<RoutePoint>?,
        recordedAtEpochMillis: Long?,
        recordedBy: UUID?,
        recordedCartId: UUID?,
    ): Boolean {
        val targetLineId: String
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            targetLineId = line.id
            line.setRoutePoints(routePoints)
            if (recordedAtEpochMillis != null || recordedBy != null || recordedCartId != null) {
                line.setRouteRecordingMetadata(recordedAtEpochMillis, recordedBy, recordedCartId)
            }
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        rebuildRailProtection(targetLineId)
        return true
    }

    fun clearLineRoutePoints(lineId: String?): Boolean {
        val targetLineId: String
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            targetLineId = line.id
            line.clearRoutePoints()
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        rebuildRailProtection(targetLineId)
        return true
    }

    fun setLineRailProtected(lineId: String?, protectedRail: Boolean): Boolean {
        val targetLineId: String
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            targetLineId = line.id
            line.isRailProtected = protectedRail
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        rebuildRailProtection(targetLineId)
        return true
    }

    fun setLineOwner(lineId: String?, ownerId: UUID?): Boolean {
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            line.owner = ownerId
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    fun addLineAdmin(lineId: String?, adminId: UUID?): Boolean {
        val changed: Boolean
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            changed = line.addAdmin(adminId)
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
        return changed
    }

    fun removeLineAdmin(lineId: String?, adminId: UUID?): Boolean {
        val changed: Boolean
        lock.writeLock().lock()
        try {
            val line = lines[lineId] ?: return false
            changed = line.removeAdmin(adminId)
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            saveConfig()
        }
        return changed
    }

    private fun indexLineStops(line: Line?) {
        if (line == null) {
            return
        }
        for (stopId in line.orderedStopIds) {
            stopToLinesIndex.computeIfAbsent(stopId) { HashSet() }.add(line.id)
        }
    }

    private fun deindexLineStops(line: Line?) {
        if (line == null) {
            return
        }
        for (stopId in line.orderedStopIds) {
            val lineIds = stopToLinesIndex[stopId] ?: continue
            lineIds.remove(line.id)
            if (lineIds.isEmpty()) {
                stopToLinesIndex.remove(stopId)
            }
        }
    }

    private fun routePointsToConfig(line: Line): List<String>? {
        val routePoints = line.routePoints
        if (routePoints.isEmpty()) {
            return null
        }
        return routePoints.map { routePoint -> routePoint.toConfigString() }
    }

    private fun shouldPersistServiceConfig(line: Line): Boolean =
        line.isServiceEnabled ||
            line.headwaySeconds != Line.DEFAULT_HEADWAY_SECONDS ||
            line.dwellTicks != Line.DEFAULT_DWELL_TICKS ||
            line.trainCars != Line.DEFAULT_TRAIN_CARS ||
            line.controlMode != null

    private fun readUuid(lineId: String, key: String): UUID? {
        val uuidString = config.getString("$lineId.$key")
        if (uuidString.isNullOrEmpty()) {
            return null
        }
        return try {
            UUID.fromString(uuidString)
        } catch (_: IllegalArgumentException) {
            plugin.logger.warning("Invalid $key UUID in lines.yml for line $lineId: $uuidString")
            null
        }
    }

    private fun buildSnapshot(): String {
        val snapshot = YamlConfiguration()
        lock.readLock().lock()
        try {
            if (lines.isNotEmpty() || config.getInt(DataFileUpdater.SCHEMA_VERSION_KEY, 0) > 0) {
                snapshot.set(DataFileUpdater.SCHEMA_VERSION_KEY, DataFileUpdater.CURRENT_SCHEMA_VERSION)
            }
            val lineIds = ArrayList(lines.keys)
            lineIds.sort()
            for (lineId in lineIds) {
                val line = lines[lineId] ?: continue
                snapshot.set("$lineId.name", line.name)
                snapshot.set("$lineId.ordered_stop_ids", line.orderedStopIds)
                val portalIds = line.portalIds
                snapshot.set("$lineId.portal_ids", if (portalIds.isEmpty()) null else portalIds)
                snapshot.set("$lineId.route_points", routePointsToConfig(line))
                snapshot.set("$lineId.route_recorded_at", line.routeRecordedAtEpochMillis)
                snapshot.set("$lineId.route_recorded_by", line.routeRecordedBy?.toString())
                snapshot.set("$lineId.route_recorded_cart", line.routeRecordedCartId?.toString())
                snapshot.set("$lineId.color", line.color)
                snapshot.set("$lineId.terminus_name", line.terminusName)
                snapshot.set("$lineId.max_speed", line.getMaxSpeed())
                snapshot.set("$lineId.ticket_price", line.ticketPrice.takeIf { price -> price > 0 })

                if (shouldPersistServiceConfig(line)) {
                    snapshot.set("$lineId.service.enabled", line.isServiceEnabled)
                    snapshot.set("$lineId.service.headway_seconds", line.headwaySeconds)
                    snapshot.set("$lineId.service.dwell_ticks", line.dwellTicks)
                    snapshot.set("$lineId.service.train_cars", line.trainCars)
                    snapshot.set(
                        "$lineId.service.control_mode",
                        line.controlMode?.name?.lowercase(Locale.getDefault()),
                    )
                }
                snapshot.set(
                    "$lineId.entity_type",
                    line.entityTypeOverride?.lowercase(Locale.getDefault()),
                )

                val priceRule = line.priceRule
                if (priceRule != null) {
                    for ((key, value) in priceRule.serialize()) {
                        snapshot.set("$lineId.price_rule.$key", value)
                    }
                }
                if (line.getLineStatus() != LineStatus.NORMAL) {
                    snapshot.set("$lineId.line_status", line.getLineStatus().getConfigKey())
                }
                val alternativeRoutes = line.alternativeRouteIds
                if (alternativeRoutes.isNotEmpty()) {
                    snapshot.set("$lineId.alternative_routes", alternativeRoutes)
                }
                val suspensionMessage = line.suspensionMessage
                if (!suspensionMessage.isNullOrEmpty()) {
                    snapshot.set("$lineId.suspension_message", suspensionMessage)
                }
                snapshot.set("$lineId.rail_protected", true.takeIf { line.isRailProtected })
                snapshot.set("$lineId.owner", line.owner?.toString())

                val adminStrings = ArrayList<String>()
                for (adminId in line.admins) {
                    if (line.owner != null && line.owner == adminId) {
                        continue
                    }
                    adminStrings.add(adminId.toString())
                }
                adminStrings.sort()
                snapshot.set("$lineId.admins", if (adminStrings.isEmpty()) null else adminStrings)
                snapshot.set("$lineId.world", line.worldName)
            }
            return snapshot.saveToString()
        } finally {
            lock.readLock().unlock()
        }
    }

    fun cloneReverseLine(
        sourceLineId: String?,
        newLineId: String?,
        stopIdSuffix: String?,
        ownerId: UUID?,
    ): Boolean {
        val requiredNewLineId = newLineId ?: throw NullPointerException("newLineId")
        lock.writeLock().lock()
        try {
            val sourceLine = lines[sourceLineId] ?: return false
            if (lines.containsKey(requiredNewLineId)) {
                return false
            }

            val newLine = Line(requiredNewLineId, sourceLine.name)
            newLine.owner = ownerId
            newLine.color = sourceLine.color
            newLine.setMaxSpeed(sourceLine.getMaxSpeed())
            newLine.worldName = sourceLine.worldName
            newLine.isServiceEnabled = sourceLine.isServiceEnabled
            newLine.headwaySeconds = sourceLine.headwaySeconds
            newLine.dwellTicks = sourceLine.dwellTicks
            newLine.trainCars = sourceLine.trainCars
            newLine.controlMode = sourceLine.controlMode
            if (sourceLine.hasEntityTypeOverride()) {
                newLine.setEntityType(sourceLine.getEntityType())
            }

            val newAdmins = HashSet(sourceLine.admins)
            if (ownerId != null) {
                newAdmins.add(ownerId)
            }
            newLine.setAdmins(newAdmins)
            lines[requiredNewLineId] = newLine

            val stopManager = plugin.stopManager
            val sourceStops = sourceLine.orderedStopIds
            for (index in sourceStops.size - 1 downTo 0) {
                val oldStopId = sourceStops[index]
                val oldStop = stopManager.getStop(oldStopId) ?: continue
                val newStopId = oldStopId + stopIdSuffix
                var newStop = stopManager.getStop(newStopId)
                if (newStop == null) {
                    newStop = stopManager.createStop(
                        newStopId,
                        oldStop.name,
                        oldStop.corner1,
                        oldStop.corner2,
                        ownerId,
                    )
                    if (newStop != null) {
                        var newYaw = (oldStop.launchYaw + 180.0f) % 360.0f
                        if (newYaw > 180.0f) newYaw -= 360.0f
                        if (newYaw < -180.0f) newYaw += 360.0f
                        stopManager.setStopPoint(newStopId, oldStop.stopPointLocation, newYaw)
                        for (adminId in oldStop.admins) {
                            stopManager.addStopAdmin(newStopId, adminId)
                        }
                    }
                }
                newLine.addStop(newStopId, -1)
            }
            indexLineStops(newLine)
        } finally {
            lock.writeLock().unlock()
        }
        saveConfig()
        return true
    }

    private fun rebuildRailProtection(lineId: String) {
        plugin.railProtectionManager?.rebuildLine(lineId)
    }
}
