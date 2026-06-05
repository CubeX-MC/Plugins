package org.cubexmc.mountlicense.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class CooldownTracker {
    private val until: MutableMap<UUID, Long> = ConcurrentHashMap()

    fun tryAcquire(player: UUID, seconds: Int): Boolean {
        if (seconds <= 0) return true
        val now = System.currentTimeMillis()
        val expiresAt = until[player]
        if (expiresAt != null && expiresAt > now) return false
        until[player] = now + seconds * 1000L
        return true
    }

    fun remainingSeconds(player: UUID): Long {
        val expiresAt = until[player] ?: return 0
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining <= 0) 0 else max(1, remaining / 1000L)
    }

    fun clear(player: UUID) {
        until.remove(player)
    }
}
