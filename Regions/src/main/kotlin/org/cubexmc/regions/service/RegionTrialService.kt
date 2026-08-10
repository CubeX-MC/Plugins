package org.cubexmc.regions.service

import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.ValidationSeverity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RegionTrial(
    val playerId: UUID,
    val regionId: String,
    val revision: Long,
    val syntheticRegionId: String,
)

class RegionTrialService(private val plugin: RegionsPlugin) {
    private val trials: MutableMap<UUID, RegionTrial> = ConcurrentHashMap()

    fun start(player: Player, regionId: String): ServiceResult {
        val draft = plugin.publishing().draft(regionId)
            ?: return ServiceResult.fail("Region has no draft: $regionId")
        val authority = plugin.authority().canManage(player, draft)
        if (!authority.allowed) {
            return ServiceResult.fail(authority.denial?.messageKey ?: "no-permission")
        }
        val errors = plugin.publishing().previewIssues(player, regionId)
            .filter { it.severity == ValidationSeverity.ERROR }
        if (errors.isNotEmpty()) {
            return ServiceResult.fail(errors.joinToString("; ") { it.message })
        }
        stop(player, "trial-replaced")
        val syntheticId = "trial_${player.uniqueId.toString().replace("-", "").take(12)}"
        val synthetic = draft.copy(id = syntheticId, name = "${draft.name} [Trial]")
        val resolution = plugin.overlaps().resolve(listOf(draft))
        for (effect in resolution.effects) {
            val applied = plugin.effects().apply(player, synthetic, effect.config)
            if (!applied.success) {
                plugin.effects().cleanupRegion(player, syntheticId, "trial-start-failed")
                return ServiceResult.fail(
                    "Trial effect failed: ${applied.reason.ifBlank { "unknown error" }}; trial was rolled back",
                )
            }
        }
        trials[player.uniqueId] = RegionTrial(player.uniqueId, regionId, draft.revision, syntheticId)
        plugin.audit().record(
            player,
            regionId,
            "region.trial.start",
            details = mapOf("revision" to draft.revision.toString()),
        )
        return ServiceResult.ok()
    }

    fun stop(player: Player, reason: String = "trial-stop"): ServiceResult {
        val trial = trials.remove(player.uniqueId) ?: return ServiceResult.ok()
        plugin.effects().cleanupRegion(player, trial.syntheticRegionId, reason)
        plugin.audit().record(
            player,
            trial.regionId,
            "region.trial.end",
            reason,
            mapOf("revision" to trial.revision.toString()),
        )
        return ServiceResult.ok()
    }

    fun active(playerId: UUID): RegionTrial? = trials[playerId]

    fun activeDraft(playerId: UUID): RegionDefinition? {
        val trial = trials[playerId] ?: return null
        val draft = plugin.publishing().draft(trial.regionId) ?: return null
        return draft.takeIf { it.revision == trial.revision }
    }

    fun cleanupAll(reason: String, shuttingDown: Boolean = false): Int {
        val current = trials.values.toList()
        for (trial in current) {
            val player = plugin.server.getPlayer(trial.playerId)
            if (player == null) {
                trials.remove(trial.playerId)
            } else if (!plugin.regionScheduler().isFolia) {
                stop(player, reason)
            } else if (!shuttingDown) {
                plugin.regionScheduler().runAtEntity(player, Runnable { stop(player, reason) })
            }
        }
        if (shuttingDown) trials.clear()
        return current.size
    }

    fun stopRegion(regionId: String, reason: String): Int {
        val matching = trials.values.filter { it.regionId.equals(regionId, ignoreCase = true) }
        for (trial in matching) {
            val player = plugin.server.getPlayer(trial.playerId)
            if (player != null) stop(player, reason)
            else trials.remove(trial.playerId)
        }
        return matching.size
    }
}
