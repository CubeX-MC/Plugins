package org.cubexmc.metro.model

import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import kotlin.math.abs

/**
 * 代表一个矿车传送门。
 * 当 Metro 矿车经过传送门入口铁轨下方的触发方块时，
 * 矿车和乘客将被传送到目标位置。
 */
class Portal(val id: String) {
    // 入口坐标（方块级精度）
    var worldName: String? = null
        private set
    var x: Int = 0
        private set
    var y: Int = 0
        private set
    var z: Int = 0
        private set

    // 目标坐标（精确级精度）
    var destWorldName: String? = null
        private set
    var destX: Double = 0.0
        private set
    var destY: Double = 0.0
        private set
    var destZ: Double = 0.0
        private set
    var destYaw: Float = 0.0f
        private set

    var owner: UUID? = null
        set(value) {
            field = value
            if (value != null) {
                adminIds.add(value)
            }
            adminIds.remove(null)
        }
    private val adminIds: MutableSet<UUID?> = HashSet()

    // =============== 序列化 / 反序列化 ===============
    /**
     * 序列化到 ConfigurationSection
     */
    fun toConfig(section: ConfigurationSection) {
        section.set("world", worldName)
        section.set("x", x)
        section.set("y", y)
        section.set("z", z)
        section.set("dest_world", destWorldName)
        section.set("dest_x", destX)
        section.set("dest_y", destY)
        section.set("dest_z", destZ)
        section.set("dest_yaw", destYaw.toDouble())
        section.set("owner", owner?.toString())
        if (adminIds.isNotEmpty()) {
            val adminStrings = adminIds
                .filterNotNull()
                .filter { adminId -> owner == null || owner != adminId }
                .map { adminId -> adminId.toString() }
            section.set("admins", adminStrings)
        }
    }

    // =============== 核心方法 ===============
    /**
     * 检查给定位置的方块坐标是否匹配此传送门入口
     * 允许 Y 轴有 ±1 的误差，因为玩家创建坐标和矿车经过坐标可能微小不一致。
     */
    fun matchesLocation(loc: Location?): Boolean {
        if (loc == null || loc.world == null) return false
        val world = loc.world ?: return false
        return world.name == worldName &&
            loc.blockX == x &&
            abs(loc.blockY - y) <= 1 &&
            loc.blockZ == z
    }

    /**
     * 获取目标 Location 对象
     */
    fun getDestination(): Location? {
        val world = Bukkit.getWorld(destWorldName ?: return null) ?: return null
        val dest = Location(world, destX, destY, destZ)
        dest.yaw = destYaw
        return dest
    }

    /**
     * 从玩家当前位置设置入口点（方块坐标）
     */
    fun setEntrance(loc: Location) {
        val world = loc.world ?: throw NullPointerException("Location world is null")
        worldName = world.name
        x = loc.blockX
        y = loc.blockY
        z = loc.blockZ
    }

    /**
     * 从玩家当前位置设置目标点（精确坐标）
     */
    fun setDestination(loc: Location) {
        val world = loc.world ?: throw NullPointerException("Location world is null")
        destWorldName = world.name
        destX = loc.x
        destY = loc.y
        destZ = loc.z
        destYaw = loc.yaw
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

    companion object {
        // =============== 序列化 / 反序列化 ===============
        /**
         * 从 ConfigurationSection 反序列化
         */
        @JvmStatic
        fun fromConfig(id: String, section: ConfigurationSection): Portal {
            val portal = Portal(id)
            portal.worldName = section.getString("world", "world")
            portal.x = section.getInt("x")
            portal.y = section.getInt("y")
            portal.z = section.getInt("z")
            portal.destWorldName = section.getString("dest_world", "world")
            portal.destX = section.getDouble("dest_x")
            portal.destY = section.getDouble("dest_y")
            portal.destZ = section.getDouble("dest_z")
            portal.destYaw = section.getDouble("dest_yaw", 0.0).toFloat()
            val ownerString = section.getString("owner")
            if (ownerString != null && ownerString.isNotEmpty()) {
                try {
                    portal.owner = UUID.fromString(ownerString)
                    portal.adminIds.add(portal.owner)
                } catch (ignored: IllegalArgumentException) {
                    portal.owner = null
                }
            }

            val adminStrings = section.getStringList("admins")
            if (adminStrings != null) {
                for (entry in adminStrings) {
                    try {
                        portal.adminIds.add(UUID.fromString(entry))
                    } catch (ignored: IllegalArgumentException) {
                    }
                }
            }
            portal.adminIds.remove(null)
            return portal
        }
    }
}
