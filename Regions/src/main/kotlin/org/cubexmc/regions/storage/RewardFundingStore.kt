package org.cubexmc.regions.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexLogger
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class RewardFundingStore(
    private val file: File,
    private val logger: CubexLogger,
) : Reloadable, Terminable {
    private val leases: MutableMap<String, Lease> = LinkedHashMap()
    private var dirty = false

    @Synchronized
    fun get(regionId: String): Lease? = leases[regionId]

    @Synchronized
    fun all(): List<Lease> = leases.values.toList()

    @Synchronized
    fun put(lease: Lease) {
        leases[lease.regionId] = lease
        dirty = true
    }

    @Synchronized
    fun remove(regionId: String) {
        if (leases.remove(regionId) != null) dirty = true
    }

    @Synchronized
    fun save(): Boolean {
        val yaml = YamlConfiguration()
        for (lease in leases.values) {
            val path = "leases.${lease.regionId}"
            yaml["$path.contract-id"] = lease.contractId
            yaml["$path.operation-id"] = lease.operationId
            yaml["$path.state"] = lease.state.name
            yaml["$path.winner-id"] = lease.winnerId?.toString()
            yaml["$path.reason"] = lease.reason
            yaml["$path.created-at"] = lease.createdAt
        }
        return runCatching {
            val target = file.toPath()
            Files.createDirectories(target.parent)
            val temp = Files.createTempFile(target.parent, "reward-funding", ".tmp")
            try {
                yaml.save(temp.toFile())
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (ex: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
            dirty = false
            true
        }.getOrElse {
            logger.warn("Failed to save reward funding leases; keeping them in memory.", it)
            false
        }
    }

    @Synchronized
    override fun reload() {
        val loaded = LinkedHashMap<String, Lease>()
        if (file.exists()) {
            val root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("leases")
            if (root != null) {
                for (regionId in root.getKeys(false)) {
                    val section = root.getConfigurationSection(regionId) ?: continue
                    runCatching {
                        Lease(
                            regionId,
                            section.getString("contract-id") ?: error("contract-id missing"),
                            section.getString("operation-id") ?: error("operation-id missing"),
                            LeaseState.valueOf(section.getString("state", LeaseState.PREPARING.name)!!),
                            section.getString("winner-id")?.takeIf(String::isNotBlank)?.let(UUID::fromString),
                            section.getString("reason", "") ?: "",
                            section.getLong("created-at"),
                        )
                    }.onSuccess { loaded[regionId] = it }
                        .onFailure { logger.warn("Skipping malformed reward funding lease $regionId: ${it.message}") }
                }
            }
        }
        leases.clear()
        leases.putAll(loaded)
        dirty = false
    }

    @Synchronized
    override fun close() {
        if (dirty) save()
    }

    data class Lease(
        val regionId: String,
        val contractId: String,
        val operationId: String,
        var state: LeaseState,
        var winnerId: UUID? = null,
        var reason: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    enum class LeaseState {
        PREPARING,
        LOCKED,
        SETTLING,
        REFUNDING,
        TERMINAL,
    }
}
