package org.cubexmc.metro.manager

import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.RoutePoint
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class RouteRecorder(
    private val plugin: Metro,
) {
    private val defaultMinSampleDistanceBlocks = 1.0
    private val defaultSimplifyEpsilonBlocks = 0.15
    private val minSavePoints = 2
    private val sessions: MutableMap<String, RecordingSession> = ConcurrentHashMap()
    private val routeNormalizer = RouteNormalizer()

    fun start(lineId: String): Boolean = start(lineId, null)

    fun start(lineId: String, recorderId: UUID?): Boolean =
        sessions.putIfAbsent(lineId, RecordingSession(lineId, recorderId)) == null

    fun stopAndSave(lineId: String): FinishResult {
        val session = sessions.remove(lineId) ?: return notRecording(lineId)
        return saveSession(session)
    }

    fun clearActive(lineId: String): Boolean = sessions.remove(lineId) != null

    fun isRecording(lineId: String): Boolean = sessions.containsKey(lineId)

    fun getActivePointCount(lineId: String): Int = sessions[lineId]?.pointCount() ?: 0

    fun getRecordingCartId(lineId: String): UUID? = sessions[lineId]?.cartId()

    fun getRecordingPlayerId(lineId: String): UUID? = sessions[lineId]?.recorderId

    fun transferCart(lineId: String, previousCart: Minecart?, newCart: Minecart?): Boolean {
        val session = sessions[lineId]
        if (session == null || previousCart == null || newCart == null) {
            return false
        }
        return session.transferCart(previousCart.uniqueId, newCart.uniqueId)
    }

    fun sample(lineId: String, minecart: Minecart?, location: Location?) {
        val session = sessions[lineId]
        if (session == null || minecart == null || location == null) {
            return
        }
        val routePoint = RoutePoint.fromLocation(location) ?: return
        session.sample(minecart.uniqueId, routePoint, minSampleDistanceSquared())
    }

    fun finishIfRecording(lineId: String, minecart: Minecart?): FinishResult {
        val session = sessions[lineId]
        if (session == null || minecart == null || !session.matchesCart(minecart.uniqueId)) {
            return notRecording(lineId)
        }
        sessions.remove(lineId)
        return saveSession(session)
    }

    fun cancelAll() {
        sessions.clear()
    }

    private fun saveSession(session: RecordingSession): FinishResult {
        val snapshot = session.snapshot()
        val normalized = routeNormalizer.normalize(snapshot, simplifyEpsilonBlocks())
        val points = if (normalized.size >= minSavePoints) {
            normalized
        } else {
            simplifyRoutePoints(snapshot)
        }
        if (points.size < minSavePoints) {
            return FinishResult(
                FinishResult.Status.TOO_FEW_POINTS,
                session.lineId,
                points.size,
                session.recorderId,
                session.cartId(),
            )
        }
        if (!plugin.lineManager.setLineRoutePoints(
                session.lineId,
                points,
                System.currentTimeMillis(),
                session.recorderId,
                session.cartId(),
            )
        ) {
            return FinishResult(
                FinishResult.Status.FAILED,
                session.lineId,
                points.size,
                session.recorderId,
                session.cartId(),
            )
        }
        plugin.logger.info("[RouteRecorder] Saved ${points.size} route points for line ${session.lineId}.")
        return FinishResult(
            FinishResult.Status.SAVED,
            session.lineId,
            points.size,
            session.recorderId,
            session.cartId(),
        )
    }

    private fun notRecording(lineId: String): FinishResult =
        FinishResult(FinishResult.Status.NOT_RECORDING, lineId, 0, null, null)

    private fun simplifyRoutePoints(points: List<RoutePoint>?): List<RoutePoint> {
        if (points == null || points.size < 3 || !shouldSimplifyCollinearPoints()) {
            return points ?: emptyList()
        }
        val simplified = ArrayList<RoutePoint>()
        simplified.add(points[0])
        val epsilon = simplifyEpsilonBlocks()
        for (index in 1 until points.size - 1) {
            val previous = simplified[simplified.size - 1]
            val current = points[index]
            val next = points[index + 1]
            if (!isRedundantCollinearPoint(previous, current, next, epsilon)) {
                simplified.add(current)
            }
        }
        simplified.add(points[points.size - 1])
        return simplified
    }

    private fun shouldSimplifyCollinearPoints(): Boolean =
        plugin.configFacade == null || plugin.configFacade.isRouteRecordingSimplifyCollinearPoints()

    private fun minSampleDistanceSquared(): Double {
        val distance = if (plugin.configFacade == null) {
            defaultMinSampleDistanceBlocks
        } else {
            plugin.configFacade.getRouteRecordingMinSampleDistanceBlocks()
        }
        return distance * distance
    }

    private fun simplifyEpsilonBlocks(): Double =
        if (plugin.configFacade == null) {
            defaultSimplifyEpsilonBlocks
        } else {
            plugin.configFacade.getRouteRecordingSimplifyEpsilonBlocks()
        }

    private class RecordingSession(
        val lineId: String,
        val recorderId: UUID?,
    ) {
        private val points = ArrayList<RoutePoint>()
        private var cartId: UUID? = null
        private var lastPoint: RoutePoint? = null

        @Synchronized
        fun cartId(): UUID? = cartId

        @Synchronized
        fun sample(candidateCartId: UUID, routePoint: RoutePoint, minSampleDistanceSquared: Double) {
            if (cartId == null) {
                cartId = candidateCartId
            }
            if (cartId != candidateCartId) {
                return
            }
            val previousPoint = lastPoint
            if (previousPoint != null && previousPoint.distanceSquared(routePoint) < minSampleDistanceSquared) {
                return
            }
            points.add(routePoint)
            lastPoint = routePoint
        }

        @Synchronized
        fun matchesCart(candidateCartId: UUID): Boolean = cartId == null || cartId == candidateCartId

        @Synchronized
        fun transferCart(previousCartId: UUID?, newCartId: UUID?): Boolean {
            if (previousCartId == null || newCartId == null) {
                return false
            }
            if (cartId == null) {
                cartId = newCartId
                return true
            }
            if (cartId == newCartId) {
                return true
            }
            if (cartId != previousCartId) {
                return false
            }
            cartId = newCartId
            return true
        }

        @Synchronized
        fun pointCount(): Int = points.size

        @Synchronized
        fun snapshot(): List<RoutePoint> = ArrayList(points)
    }

    @JvmRecord
    data class FinishResult(
        val status: Status,
        val lineId: String,
        val pointCount: Int,
        val recorderId: UUID?,
        val cartId: UUID?,
    ) {
        enum class Status {
            SAVED,
            NOT_RECORDING,
            TOO_FEW_POINTS,
            FAILED,
        }
    }

    private fun isRedundantCollinearPoint(
        previous: RoutePoint?,
        current: RoutePoint?,
        next: RoutePoint?,
        epsilon: Double,
    ): Boolean {
        if (previous == null || current == null || next == null ||
            previous.worldName() != current.worldName() ||
            previous.worldName() != next.worldName()
        ) {
            return false
        }
        val acX = next.x() - previous.x()
        val acY = next.y() - previous.y()
        val acZ = next.z() - previous.z()
        val abX = current.x() - previous.x()
        val abY = current.y() - previous.y()
        val abZ = current.z() - previous.z()
        val acLengthSquared = acX * acX + acY * acY + acZ * acZ
        if (acLengthSquared <= 0.000001) {
            return current.distanceSquared(previous) <= epsilon * epsilon
        }
        val projection = abX * acX + abY * acY + abZ * acZ
        val tolerance = max(epsilon, 0.000001)
        if (projection < -tolerance || projection > acLengthSquared + tolerance) {
            return false
        }
        val crossX = abY * acZ - abZ * acY
        val crossY = abZ * acX - abX * acZ
        val crossZ = abX * acY - abY * acX
        val distanceSquared =
            (crossX * crossX + crossY * crossY + crossZ * crossZ) / acLengthSquared
        return distanceSquared <= epsilon * epsilon
    }
}
