package org.cubexmc.metro.model

import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.util.BoundingBox

/**
 * 代表地铁系统中的停靠区
 */
class Stop {
    val id: String
    var name: String
    var corner1: Location? = null
        set(value) {
            field = value
            updateBoundsCache()
        }
    var corner2: Location? = null
        set(value) {
            field = value
            updateBoundsCache()
        }
    var stopPointLocation: Location? = null
    var launchYaw: Float = 0.0f
    private var transferableLineList: MutableList<String> = ArrayList()
    var owner: UUID? = null
        set(value) {
            field = value
            if (value != null) {
                adminIds.add(value)
            }
            adminIds.remove(null)
        }
    private val adminIds: MutableSet<UUID?> = HashSet()
    private val linkedLineIdSet: MutableSet<String> = HashSet()

    // 自定义titles配置
    private val customTitles: MutableMap<String, MutableMap<String, String>?> = HashMap()

    // 由 corner1/corner2 计算的轴对齐包围盒，为 null 表示尚未设定区域
    var boundingBox: BoundingBox? = null
        private set

    /**
     * 创建新停靠区
     *
     * @param id 停靠区ID
     * @param name 停靠区名称
     */
    constructor(id: String, name: String) {
        this.id = id
        this.name = name
    }

    /**
     * 从配置节加载停靠区
     *
     * @param id 停靠区ID
     * @param section 配置节
     */
    constructor(id: String, section: ConfigurationSection) {
        this.id = id
        this.name = section.getString("display_name", "") ?: ""

        val corner1String = section.getString("corner1_location")
        if (corner1String != null) {
            this.corner1 = locationFromString(corner1String)
        }

        val corner2String = section.getString("corner2_location")
        if (corner2String != null) {
            this.corner2 = locationFromString(corner2String)
        }

        val locString = section.getString("stoppoint_location")
        if (locString != null) {
            stopPointLocation = locationFromString(locString)
        }

        launchYaw = section.getDouble("launch_yaw", 0.0).toFloat()

        // 加载可换乘线路ID列表
        transferableLineList = ArrayList(section.getStringList("transferable_lines"))

        // 加载自定义titles配置
        val customTitlesSection = section.getConfigurationSection("custom_titles")
        if (customTitlesSection != null) {
            for (titleType in customTitlesSection.getKeys(false)) {
                val titleTypeSection = customTitlesSection.getConfigurationSection(titleType)
                if (titleTypeSection != null) {
                    val titleConfig: MutableMap<String, String> = HashMap()
                    for (key in titleTypeSection.getKeys(false)) {
                        titleConfig[key] = titleTypeSection.getString(key) ?: ""
                    }
                    customTitles[titleType] = titleConfig
                }
            }
        }

        val ownerString = section.getString("owner")
        if (ownerString != null && ownerString.isNotEmpty()) {
            try {
                owner = UUID.fromString(ownerString)
                adminIds.add(owner)
            } catch (ignored: IllegalArgumentException) {
                owner = null
            }
        }

        val adminStrings = section.getStringList("admins")
        if (adminStrings != null) {
            for (entry in adminStrings) {
                try {
                    val adminId = UUID.fromString(entry)
                    adminIds.add(adminId)
                } catch (ignored: IllegalArgumentException) {
                }
            }
        }

        val linkedLines = section.getStringList("linked_lines")
        if (linkedLines != null) {
            linkedLineIdSet.addAll(linkedLines)
        }

        // 初始化边界缓存
        updateBoundsCache()
    }

    /**
     * 将停靠区保存到配置节
     *
     * @param section 目标配置节
     */
    fun saveToConfig(section: ConfigurationSection) {
        section.set("display_name", name)

        val currentCorner1 = corner1
        if (currentCorner1 != null) {
            section.set("corner1_location", locationToString(currentCorner1))
        }

        val currentCorner2 = corner2
        if (currentCorner2 != null) {
            section.set("corner2_location", locationToString(currentCorner2))
        }

        val currentStopPoint = stopPointLocation
        if (currentStopPoint != null) {
            section.set("stoppoint_location", locationToString(currentStopPoint))
        }

        section.set("launch_yaw", launchYaw)

        // 保存可换乘线路ID列表
        section.set("transferable_lines", transferableLineList)

        section.set("owner", owner?.toString())
        if (adminIds.isNotEmpty()) {
            val adminStrings: MutableList<String> = ArrayList()
            for (adminId in adminIds.filterNotNull()) {
                // 避免重复写入所有者
                if (owner != null && owner == adminId) {
                    continue
                }
                adminStrings.add(adminId.toString())
            }
            section.set("admins", adminStrings)
        } else {
            section.set("admins", null)
        }

        if (linkedLineIdSet.isNotEmpty()) {
            section.set("linked_lines", ArrayList(linkedLineIdSet))
        } else {
            section.set("linked_lines", null)
        }

        // 保存自定义titles配置
        if (customTitles.isNotEmpty()) {
            val customTitlesSection = section.createSection("custom_titles")
            for ((titleType, titleConfig) in customTitles) {
                if (titleConfig != null && titleConfig.isNotEmpty()) {
                    val titleTypeSection = customTitlesSection.createSection(titleType)
                    for ((key, value) in titleConfig) {
                        titleTypeSection.set(key, value)
                    }
                }
            }
        }
    }

    /**
     * 获取站点自定义title配置
     *
     * @param titleType title类型(stop_continuous, arrive_stop, terminal_stop, departure)
     * @return 配置Map，如果不存在则返回null
     */
    fun getCustomTitle(titleType: String): MutableMap<String, String>? = customTitles[titleType]

    /**
     * 设置站点自定义title配置
     *
     * @param titleType title类型(stop_continuous, arrive_stop, terminal_stop, departure)
     * @param config title配置
     */
    fun setCustomTitle(titleType: String, config: MutableMap<String, String>?) {
        customTitles[titleType] = config
    }

    /**
     * 移除站点自定义title配置
     *
     * @param titleType title类型(stop_continuous, arrive_stop, terminal_stop, departure)
     * @return 是否成功移除
     */
    fun removeCustomTitle(titleType: String): Boolean = customTitles.remove(titleType) != null

    /**
     * 将位置转换为字符串表示
     */
    private fun locationToString(location: Location): String {
        val world = location.world ?: throw NullPointerException("Location world is null")
        return String.format(
            "%s,%d,%d,%d",
            world.name,
            location.blockX,
            location.blockY,
            location.blockZ,
        )
    }

    /**
     * 从字符串解析位置
     */
    private fun locationFromString(locString: String): Location? {
        val parts = locString.split(",")
        if (parts.size != 4) {
            return null
        }

        return try {
            Location(
                Bukkit.getWorld(parts[0]),
                parts[1].toInt().toDouble(),
                parts[2].toInt().toDouble(),
                parts[3].toInt().toDouble(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查指定位置是否在停靠区区域内
     *
     * @param location 要检查的位置
     * @return 是否在区域内
     */
    fun isInStop(location: Location?): Boolean {
        val bounds = boundingBox
        val firstCorner = corner1
        if (bounds == null || location == null || location.world == null || firstCorner == null ||
            location.world != firstCorner.world
        ) {
            return false
        }

        return bounds.contains(location.x, location.y, location.z)
    }

    /**
     * 获取可换乘线路ID列表
     *
     * @return 可换乘线路ID列表
     */
    val transferableLines: List<String>
        get() = ArrayList(transferableLineList)

    /**
     * 添加可换乘线路
     *
     * @param lineId 线路ID
     * @return 如果线路不存在于列表中并成功添加则返回true
     */
    fun addTransferableLine(lineId: String): Boolean {
        if (!transferableLineList.contains(lineId)) {
            return transferableLineList.add(lineId)
        }
        return false
    }

    /**
     * 移除可换乘线路
     *
     * @param lineId 线路ID
     * @return 如果线路存在于列表中并成功移除则返回true
     */
    fun removeTransferableLine(lineId: String): Boolean = transferableLineList.remove(lineId)

    /**
     * 更新边界缓存，在corner1或corner2改变时调用
     */
    private fun updateBoundsCache() {
        val firstCorner = corner1
        val secondCorner = corner2
        if (firstCorner != null && secondCorner != null) {
            val minX = min(firstCorner.blockX, secondCorner.blockX)
            val maxX = max(firstCorner.blockX, secondCorner.blockX) + 1
            val minY = min(firstCorner.blockY, secondCorner.blockY)
            val maxY = max(firstCorner.blockY, secondCorner.blockY) + 1
            val minZ = min(firstCorner.blockZ, secondCorner.blockZ)
            val maxZ = max(firstCorner.blockZ, secondCorner.blockZ) + 1
            boundingBox = BoundingBox(minX.toDouble(), minY.toDouble(), minZ.toDouble(), maxX.toDouble(), maxY.toDouble(), maxZ.toDouble())
        } else {
            boundingBox = null
        }
    }

    /**
     * 获取站点所在的世界名称
     * 优先使用 stopPointLocation，如果没有则使用 corner1
     *
     * @return 世界名称，如果没有位置信息则返回 null
     */
    val worldName: String?
        get() {
            val stopPoint = stopPointLocation
            val stopPointWorld = stopPoint?.world
            if (stopPointWorld != null) {
                return stopPointWorld.name
            }
            val firstCorner = corner1
            val firstCornerWorld = firstCorner?.world
            if (firstCornerWorld != null) {
                return firstCornerWorld.name
            }
            return null
        }

    val admins: Set<UUID>
        get() = HashSet(adminIds.filterNotNull())

    fun setAdmins(adminIds: Collection<UUID>?) {
        this.adminIds.clear()
        if (adminIds != null) {
            this.adminIds.addAll(adminIds)
        }
        val currentOwner = owner
        if (currentOwner != null) {
            this.adminIds.add(currentOwner)
        }
        this.adminIds.remove(null)
    }

    fun addAdmin(adminId: UUID?): Boolean {
        if (adminId == null) {
            return false
        }
        adminIds.remove(null)
        return adminIds.add(adminId)
    }

    fun removeAdmin(adminId: UUID?): Boolean {
        if (adminId == null) {
            return false
        }
        val currentOwner = owner
        if (currentOwner != null && currentOwner == adminId) {
            return false
        }
        val removed = adminIds.remove(adminId)
        adminIds.remove(null)
        return removed
    }

    val linkedLineIds: Set<String>
        get() = HashSet(linkedLineIdSet)

    fun setLinkedLineIds(lineIds: Collection<String>?) {
        linkedLineIdSet.clear()
        if (lineIds != null) {
            linkedLineIdSet.addAll(lineIds)
        }
    }

    fun allowLine(lineId: String?): Boolean {
        if (lineId == null || lineId.isEmpty()) {
            return false
        }
        return linkedLineIdSet.add(lineId)
    }

    fun denyLine(lineId: String?): Boolean {
        if (lineId == null || lineId.isEmpty()) {
            return false
        }
        return linkedLineIdSet.remove(lineId)
    }

    fun isLineAllowed(lineId: String?): Boolean {
        if (lineId == null || lineId.isEmpty()) {
            return false
        }
        return linkedLineIdSet.contains(lineId)
    }
}
