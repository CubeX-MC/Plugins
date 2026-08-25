package org.cubexmc.reputations

import org.bukkit.Bukkit
import org.bstats.bukkit.Metrics
import org.bukkit.plugin.ServicePriority
import org.bukkit.scheduler.BukkitTask
import org.cubexmc.core.CubexPlugin
import org.cubexmc.reputations.api.ReputationChangeEvent
import org.cubexmc.reputations.api.ReputationService
import org.cubexmc.reputations.command.ReputationCommand
import org.cubexmc.reputations.gui.ReputationGui
import org.cubexmc.reputations.integration.ReputationsPlaceholderExpansion
import org.cubexmc.reputations.service.ReputationLeaderboard
import org.cubexmc.reputations.service.ReputationServiceImpl
import org.cubexmc.reputations.storage.ReputationStore
import kotlin.math.max

/**
 * Shared reputation service plugin (Vault-style). Owns the persistent store and exposes
 * [ReputationService] through the Bukkit ServicesManager; consumer plugins register their fields and
 * push updates. Holds no domain logic of its own.
 */
class ReputationsPlugin : CubexPlugin() {
    private var store: ReputationStore? = null
    private var serviceImpl: ReputationServiceImpl? = null

    override fun enablePlugin() {
        saveDefaultConfig()

        val activeStore = ReputationStore(this)
        activeStore.load()
        store = activeStore
        bind(Runnable { activeStore.flushIfDirty() })

        val service = ReputationServiceImpl(activeStore, logger) { playerId, fieldKey, previous, next, changeType ->
            server.pluginManager.callEvent(
                ReputationChangeEvent(
                    playerId,
                    fieldKey,
                    previous,
                    next,
                    changeType,
                    !Bukkit.isPrimaryThread(),
                ),
            )
        }
        serviceImpl = service
        server.servicesManager.register(ReputationService::class.java, service, this, ServicePriority.Normal)
        val leaderboard = ReputationLeaderboard(activeStore, service)

        val gui = ReputationGui(this, service, activeStore)
        registerListener(gui)

        val command = ReputationCommand(this, service, activeStore, gui, leaderboard)
        getCommand("reputation")?.let {
            it.setExecutor(command)
            it.tabCompleter = command
        }

        scheduleFlush(activeStore)
        registerPlaceholderExpansion(service, leaderboard)
        Metrics(this, BSTATS_ID)
        logger.info("Reputations enabled. Plugins can register fields via the ReputationService API.")
    }

    fun service(): ReputationService = serviceImpl ?: throw IllegalStateException("Reputation service not initialized")

    private fun scheduleFlush(activeStore: ReputationStore) {
        val periodTicks = max(5L, config.getLong("storage.flush-interval-seconds", 30)) * 20L
        val task = server.scheduler.runTaskTimer(this, Runnable { activeStore.flushIfDirty() }, periodTicks, periodTicks)
        bindTask(task) { handle -> (handle as BukkitTask).cancel() }
    }

    private fun registerPlaceholderExpansion(
        service: ReputationServiceImpl,
        leaderboard: ReputationLeaderboard,
    ) {
        if (server.pluginManager.getPlugin("PlaceholderAPI") == null) return
        try {
            if (ReputationsPlaceholderExpansion(this, service, leaderboard).register()) {
                logger.info("PlaceholderAPI expansion registered.")
            } else {
                logger.warning("PlaceholderAPI rejected the Reputations expansion; placeholders are unavailable.")
            }
        } catch (throwable: Throwable) {
            logger.log(
                java.util.logging.Level.WARNING,
                "Failed to register the PlaceholderAPI expansion; placeholders are unavailable.",
                throwable,
            )
        }
    }

    private companion object {
        const val BSTATS_ID = 31877
    }
}
