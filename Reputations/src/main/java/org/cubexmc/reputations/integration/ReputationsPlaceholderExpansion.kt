package org.cubexmc.reputations.integration

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.cubexmc.reputations.ReputationsPlugin
import org.cubexmc.reputations.service.ReputationLeaderboard
import org.cubexmc.reputations.service.ReputationServiceImpl
import java.util.Locale
import java.util.UUID

class ReputationsPlaceholderExpansion(
    private val plugin: ReputationsPlugin,
    service: ReputationServiceImpl,
    leaderboard: ReputationLeaderboard,
) : PlaceholderExpansion() {
    private val placeholders = ReputationPlaceholders(service, leaderboard)

    override fun getIdentifier(): String = "reputations"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ")

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        placeholders.resolve(player?.uniqueId, params)
}

/** Pure placeholder parser kept separate from PlaceholderAPI so query behavior is unit-testable. */
class ReputationPlaceholders(
    private val service: ReputationServiceImpl,
    private val leaderboard: ReputationLeaderboard,
) {
    fun resolve(playerId: UUID?, params: String): String? {
        val lower = params.lowercase(Locale.ROOT)
        return when {
            lower.startsWith(VALUE_PREFIX) -> playerValue(playerId, params.substring(VALUE_PREFIX.length))
            lower.startsWith(RANK_PREFIX) -> playerRank(playerId, params.substring(RANK_PREFIX.length))
            lower.startsWith(TOP_NAME_PREFIX) -> top(params.substring(TOP_NAME_PREFIX.length), name = true)
            lower.startsWith(TOP_VALUE_PREFIX) -> top(params.substring(TOP_VALUE_PREFIX.length), name = false)
            else -> null
        }
    }

    private fun playerValue(playerId: UUID?, requestedKey: String): String? {
        val field = leaderboard.resolveField(requestedKey) ?: return null
        return playerId?.let { format(service.get(it, field.key())) } ?: "0"
    }

    private fun playerRank(playerId: UUID?, requestedKey: String): String? {
        val field = leaderboard.resolveField(requestedKey) ?: return null
        return playerId?.let { leaderboard.rankOf(it, field.key())?.rank?.toString() } ?: "0"
    }

    private fun top(value: String, name: Boolean): String? {
        val separator = value.indexOf('_')
        if (separator <= 0 || separator == value.lastIndex) return null
        val position = value.substring(0, separator).toIntOrNull()?.takeIf { it > 0 } ?: return null
        val field = leaderboard.resolveField(value.substring(separator + 1)) ?: return null
        val entry = leaderboard.entries(field.key()).getOrNull(position - 1) ?: return ""
        return if (name) entry.playerName else format(entry.value)
    }

    private fun format(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else value.toString()

    private companion object {
        const val VALUE_PREFIX = "value_"
        const val RANK_PREFIX = "rank_"
        const val TOP_NAME_PREFIX = "top_name_"
        const val TOP_VALUE_PREFIX = "top_value_"
    }
}
