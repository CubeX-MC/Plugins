package org.cubexmc.metro.model

import java.util.UUID
import kotlin.math.max

/**
 * 代表地铁系统中的一条线路
 */
class Line(
    val id: String,
    var name: String,
) {
    private val orderedStopIdList: MutableList<String> = ArrayList()
    private val portalIdList: MutableList<String> = ArrayList()
    private var routePointList: MutableList<RoutePoint> = ArrayList()
    var color: String = "&f" // 默认白色
    var terminusName: String = "" // 默认空
    private var maxSpeedValue: Double? = null // 默认使用config.yml中的maxspeed
    var ticketPrice: Double = 0.0 // 默认免费
        set(value) {
            field = max(0.0, value)
        }
    var isRailProtected: Boolean = false // 是否保护已记录线路上的铁轨
    var routeRecordedAtEpochMillis: Long? = null
        set(value) {
            field = if (value != null && value > 0) value else null
        }
    var routeRecordedBy: UUID? = null
    var routeRecordedCartId: UUID? = null
    var owner: UUID? = null // 线路所有者 UUID，null 表示服务器所有
        set(value) {
            field = value
            if (value != null) {
                adminIds.add(value)
            }
            adminIds.remove(null)
        }
    private val adminIds: MutableSet<UUID?> = HashSet() // 线路管理员 UUID 集合
    var worldName: String? = null // 线路所在世界名称，null 表示还未添加任何站点
    var priceRule: PriceRule? = null
    private var status: LineStatus? = LineStatus.NORMAL
    private val alternativeRouteIdList: MutableList<String> = ArrayList()
    var suspensionMessage: String? = null

    /**
     * 获取线路最大速度
     *
     * @return 线路最大速度，如果未设置则返回n-1.0
     */
    fun getMaxSpeed(): Double? = maxSpeedValue ?: -1.0

    /**
     * 设置线路最大速度
     *
     * @param maxSpeed 新的最大速度
     */
    fun setMaxSpeed(maxSpeed: Double?) {
        maxSpeedValue = maxSpeed
    }

    /**
     * 获取有序停靠区ID列表
     *
     * @return 有序停靠区ID列表
     */
    val orderedStopIds: List<String>
        get() = ArrayList(orderedStopIdList)

    val portalIds: List<String>
        get() = ArrayList(portalIdList)

    val routePoints: List<RoutePoint>
        get() = ArrayList(routePointList)

    fun setRoutePoints(routePoints: Collection<RoutePoint?>?) {
        routePointList = ArrayList()
        if (routePoints != null) {
            for (point in routePoints) {
                if (point != null) {
                    routePointList.add(point)
                }
            }
        }
    }

    fun clearRoutePoints() {
        routePointList.clear()
        clearRouteRecordingMetadata()
    }

    fun setRouteRecordingMetadata(recordedAtEpochMillis: Long?, recordedBy: UUID?, recordedCartId: UUID?) {
        routeRecordedAtEpochMillis = recordedAtEpochMillis
        routeRecordedBy = recordedBy
        routeRecordedCartId = recordedCartId
    }

    fun clearRouteRecordingMetadata() {
        routeRecordedAtEpochMillis = null
        routeRecordedBy = null
        routeRecordedCartId = null
    }

    /**
     * 检查线路是否为环线
     *
     * @return 是否为环线
     */
    val isCircular: Boolean
        get() {
            if (orderedStopIdList.isEmpty() || orderedStopIdList.size < 2) {
                return false
            }
            return orderedStopIdList[0] == orderedStopIdList[orderedStopIdList.size - 1]
        }

    /**
     * 向线路添加停靠区
     *
     * @param stopId 停靠区ID
     * @param index 添加位置，-1表示添加到末尾
     */
    fun addStop(stopId: String, index: Int) {
        // 先移除，防止重复
        val isMakingCircular = !isCircular &&
            orderedStopIdList.isNotEmpty() &&
            orderedStopIdList[0] == stopId &&
            (index == -1 || index == orderedStopIdList.size)

        if (orderedStopIdList.contains(stopId) && !isMakingCircular) {
            orderedStopIdList.remove(stopId)
        }

        // 添加到指定位置或末尾
        if (isCircular && index == -1) {
            orderedStopIdList.add(orderedStopIdList.size - 1, stopId)
        } else if (index >= 0 && index < orderedStopIdList.size) {
            orderedStopIdList.add(index, stopId)
        } else {
            orderedStopIdList.add(stopId)
        }
    }

    /**
     * 从线路中移除停靠区
     *
     * @param stopId 停靠区ID
     */
    fun delStop(stopId: String) {
        orderedStopIdList.remove(stopId)
    }

    /**
     * 检查线路是否包含指定停靠区
     *
     * @param stopId 停靠区ID
     * @return 是否包含
     */
    fun containsStop(stopId: String): Boolean = orderedStopIdList.contains(stopId)

    fun addPortal(portalId: String?): Boolean {
        if (portalId == null || portalId.isBlank() || portalIdList.contains(portalId)) {
            return false
        }
        return portalIdList.add(portalId)
    }

    fun delPortal(portalId: String?): Boolean = portalIdList.remove(portalId)

    fun containsPortal(portalId: String?): Boolean = portalIdList.contains(portalId)

    /**
     * 获取指定停靠区的下一个停靠区ID
     *
     * @param currentStopId 当前停靠区ID
     * @return 下一个停靠区ID，如果当前是终点站或不存在，则返回null
     */
    fun getNextStopId(currentStopId: String?): String? {
        val index = orderedStopIdList.indexOf(currentStopId)
        if (index == -1) {
            return null
        }

        if (index == orderedStopIdList.size - 1) {
            return if (isCircular) {
                if (orderedStopIdList.size > 1) {
                    orderedStopIdList[1]
                } else {
                    orderedStopIdList[0]
                }
            } else {
                null
            }
        }
        return orderedStopIdList[index + 1]
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

    /**
     * 获取指定停靠区的上一个停靠区ID
     *
     * @param currentStopId 当前停靠区ID
     * @return 上一个停靠区ID，如果当前是起点站或不存在，则返回null
     */
    fun getPreviousStopId(currentStopId: String?): String? {
        val index = orderedStopIdList.indexOf(currentStopId)
        if (index <= 0) {
            if (isCircular) {
                if (orderedStopIdList.size > 2) {
                    return orderedStopIdList[orderedStopIdList.size - 2]
                } else if (orderedStopIdList.size == 2) {
                    return orderedStopIdList[0]
                }
            }
            return null
        }
        return orderedStopIdList[index - 1]
    }

    fun getLineStatus(): LineStatus = status ?: LineStatus.NORMAL

    fun setLineStatus(lineStatus: LineStatus?) {
        status = lineStatus ?: LineStatus.NORMAL
    }

    val alternativeRouteIds: List<String>
        get() = ArrayList(alternativeRouteIdList)

    fun setAlternativeRouteIds(alternativeRouteIds: Collection<String?>?) {
        alternativeRouteIdList.clear()
        if (alternativeRouteIds != null) {
            for (id in alternativeRouteIds) {
                if (id != null && id.trim().isNotEmpty()) {
                    alternativeRouteIdList.add(id.trim())
                }
            }
        }
    }

    fun addAlternativeRoute(lineId: String?): Boolean {
        if (lineId == null || lineId.trim().isEmpty() || alternativeRouteIdList.contains(lineId.trim())) {
            return false
        }
        return alternativeRouteIdList.add(lineId.trim())
    }

    fun removeAlternativeRoute(lineId: String?): Boolean = alternativeRouteIdList.remove(lineId)
}
