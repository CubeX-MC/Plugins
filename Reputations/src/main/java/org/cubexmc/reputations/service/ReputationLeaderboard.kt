package org.cubexmc.reputations.service

import org.cubexmc.reputations.api.ReputationField
import org.cubexmc.reputations.storage.ReputationStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stable, cached rankings over players that have a persisted value for a registered field. */
class ReputationLeaderboard(
    private val store: ReputationStore,
    private val service: ReputationServiceImpl,
) {
    private val cache = ConcurrentHashMap<String, CachedRanking>()

    fun resolveField(fieldKey: String): ReputationField? {
        service.field(fieldKey)?.let { return it }
        return service.fields().filter { it.key().equals(fieldKey, ignoreCase = true) }.singleOrNull()
    }

    fun entries(fieldKey: String): List<ReputationRankEntry> {
        val field = resolveField(fieldKey) ?: return emptyList()
        val revision = store.revision()
        cache[field.key()]?.takeIf { it.revision == revision && it.field === field }?.let { return it.entries }

        val direction = if (field.higherIsBetter()) -1 else 1
        val sorted = store.entriesFor(field.key()).sortedWith { left, right ->
            val byValue = left.value.compareTo(right.value) * direction
            if (byValue != 0) {
                byValue
            } else {
                val byName = left.playerName.lowercase(Locale.ROOT)
                    .compareTo(right.playerName.lowercase(Locale.ROOT))
                if (byName != 0) byName else left.playerId.compareTo(right.playerId)
            }
        }

        var previousValue: Double? = null
        var currentRank = 0
        val ranked = sorted.mapIndexed { index, entry ->
            if (previousValue == null || previousValue!!.compareTo(entry.value) != 0) {
                currentRank = index + 1
                previousValue = entry.value
            }
            ReputationRankEntry(currentRank, entry.playerId, entry.playerName, entry.value)
        }
        cache[field.key()] = CachedRanking(revision, field, ranked)
        return ranked
    }

    fun rankOf(playerId: UUID, fieldKey: String): ReputationRankEntry? =
        entries(fieldKey).firstOrNull { it.playerId == playerId }

    private class CachedRanking(
        val revision: Long,
        val field: ReputationField,
        val entries: List<ReputationRankEntry>,
    )
}

data class ReputationRankEntry(
    val rank: Int,
    val playerId: UUID,
    val playerName: String,
    val value: Double,
)
