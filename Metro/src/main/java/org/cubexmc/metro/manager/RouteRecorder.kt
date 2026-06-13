package org.cubexmc.metro.manager

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.RoutePoint

class RouteRecorder(private val plugin: Metro) {
    private val sessions: MutableMap<String, RecordingSession> = ConcurrentHashMap()
    private val routeNormalizer = RouteNormalizer()

    fun start(lineId: String): Boolean = start(lineId, null)

    fun start(lineId: String, recorderId: UUID?): Boolean =
        sessions.putIfAbsent(lineId, RecordingSession(lineId, recorderId)) == null

    fun stopAndSave(lineId: String): FinishResult {
        val session = sessions.remove(lineId) ?: return FinishResult.notRecording(lineId)
        return saveSession(session)
    }

    fun clearActive(lineId: String): Boolean = sessions.remove(lineId) != null

    fun isRecording(lineId: String): Boolean = sessions.containsKey(lineId)

    fun getActivePointCount(lineId: String): Int = sessions[lineId]?.pointCount() ?: 0

    fun getRecordingCartId(lineId: String): UUID? = sessions[lineId]?.cartId()

    fun getRecordingPlayerId(lineId: String): UUID? = sessions[lineId]?.recorderId()

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
            return FinishResult.notRecording(lineId)
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
        val points = if (normalized.size >= MIN_SAVE_POINTS) {
            normalized
        } else {
            simplifyRoutePoints(snapshot)
        }
        if (points.size < MIN_SAVE_POINTS) {
            return FinishResult.tooFewPoints(session.lineId(), points.size, session.recorderId(), session.cartId())
        }
        if (!plugin.lineManager.setLineRoutePoints(
                session.lineId(),
                points,
                System.currentTimeMillis(),
                session.recorderId(),
                session.cartId(),
            )
        ) {
            return FinishResult.failed(session.lineId(), points.size, session.recorderId(), session.cartId())
        }
        plugin.logger.info("[RouteRecorder] Saved ${points.size} route points for line ${session.lineId()}.")
        return FinishResult.saved(session.lineId(), points.size, session.recorderId(), session.cartId())
    }

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
        plugin.configFacade == null || plugin.configFacade.isRouteRecordingSimplifyCollinearPoints

    private fun minSampleDistanceSquared(): Double {
        val distance = if (plugin.configFacade == null) {
            DEFAULT_MIN_SAMPLE_DISTANCE_BLOCKS
        } else {
            plugin.configFacade.routeRecordingMinSampleDistanceBlocks
        }
        return distance * distance
    }

    private fun simplifyEpsilonBlocks(): Double =
        if (plugin.configFacade == null) {
            DEFAULT_SIMPLIFY_EPSILON_BLOCKS
        } else {
            plugin.configFacade.routeRecordingSimplifyEpsilonBlocks
        }

    private class RecordingSession(
        private val lineId: String,
        private val recorderId: UUID?,
    ) {
        private val points = ArrayList<RoutePoint>()
        private var cartId: UUID? = null
        private var lastPoint: RoutePoint? = null

        fun lineId(): String = lineId

        fun recorderId(): UUID? = recorderId

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

    data class FinishResult(
        private val status: Status,
        private val lineId: String,
        private val pointCount: Int,
        private val recorderId: UUID?,
        private val cartId: UUID?,
    ) {
        enum class Status {
            SAVED,
            NOT_RECORDING,
            TOO_FEW_POINTS,
            FAILED,
        }

        fun status(): Status = status

        fun lineId(): String = lineId

        fun pointCount(): Int = pointCount

        fun recorderId(): UUID? = recorderId

        fun cartId(): UUID? = cartId

        companion object {
            fun saved(lineId: String, pointCount: Int, recorderId: UUID?, cartId: UUID?): FinishResult =
                FinishResult(Status.SAVED, lineId, pointCount, recorderId, cartId)

            fun notRecording(lineId: String): FinishResult =
                FinishResult(Status.NOT_RECORDING, lineId, 0, null, null)

            fun tooFewPoints(lineId: String, pointCount: Int, recorderId: UUID?, cartId: UUID?): FinishResult =
                FinishResult(Status.TOO_FEW_POINTS, lineId, pointCount, recorderId, cartId)

            fun failed(lineId: String, pointCount: Int, recorderId: UUID?, cartId: UUID?): FinishResult =
                FinishResult(Status.FAILED, lineId, pointCount, recorderId, cartId)
        }
    }

    companion object {
        private const val DEFAULT_MIN_SAMPLE_DISTANCE_BLOCKS = 1.0
        private const val DEFAULT_SIMPLIFY_EPSILON_BLOCKS = 0.15
        private const val MIN_SAVE_POINTS = 2

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
            val tolerance = kotlin.math.max(epsilon, 0.000001)
            if (projection < -tolerance || projection > acLengthSquared + tolerance) {
                return false
            }

            val crossX = abY * acZ - abZ * acY
            val crossY = abZ * acX - abX * acZ
            val crossZ = abX * acY - abY * acX
            val distanceSquared = (crossX * crossX + crossY * crossY + crossZ * crossZ) / acLengthSquared
            return distanceSquared <= epsilon * epsilon
        }
    }
}
