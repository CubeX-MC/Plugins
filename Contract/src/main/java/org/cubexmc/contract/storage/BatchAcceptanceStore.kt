package org.cubexmc.contract.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.contract.ContractPlugin
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.logging.Logger

class BatchAcceptanceStore {
    private val file: File
    private val logger: Logger
    private val records: MutableMap<String, MutableMap<UUID, AcceptanceRecord>> = HashMap()
    private var dirty = false

    constructor(plugin: ContractPlugin) : this(File(plugin.dataFolder, "batch-acceptance.yml"), plugin.logger)

    constructor(file: File, logger: Logger) {
        this.file = file
        this.logger = logger
    }

    data class AcceptanceRecord(val acceptedAt: Long, val contractId: String)

    @Synchronized
    fun lastAcceptedAt(batchId: String, playerUuid: UUID): Long? = records[batchId]?.get(playerUuid)?.acceptedAt

    @Synchronized
    fun record(batchId: String, playerUuid: UUID, acceptedAt: Long, contractId: String): Boolean {
        val players = records.getOrPut(batchId) { HashMap() }
        val current = players[playerUuid]
        if (current != null && current.acceptedAt >= acceptedAt) {
            return false
        }
        players[playerUuid] = AcceptanceRecord(acceptedAt, contractId)
        dirty = true
        return true
    }

    @Synchronized
    fun retainBatches(batchIds: Set<String>): Int {
        val removed = records.keys.removeIf { it !in batchIds }
        if (removed) {
            dirty = true
        }
        return if (removed) 1 else 0
    }

    @Synchronized
    fun isDirty(): Boolean = dirty

    @Synchronized
    fun load() {
        records.clear()
        if (!file.exists()) {
            dirty = false
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val batches = yaml.getConfigurationSection("batches")
        if (batches != null) {
            for (batchId in batches.getKeys(false)) {
                val batch = batches.getConfigurationSection(batchId) ?: continue
                val players = HashMap<UUID, AcceptanceRecord>()
                for (uuidText in batch.getKeys(false)) {
                    val uuid = try {
                        UUID.fromString(uuidText)
                    } catch (ex: IllegalArgumentException) {
                        logger.warning("Skipping malformed batch acceptance player $uuidText in batch $batchId")
                        continue
                    }
                    val section = batch.getConfigurationSection(uuidText) ?: continue
                    players[uuid] = AcceptanceRecord(
                        section.getLong("accepted-at"),
                        section.getString("contract-id", "") ?: "",
                    )
                }
                if (players.isNotEmpty()) {
                    records[batchId] = players
                }
            }
        }
        dirty = false
    }

    @Synchronized
    @Throws(IOException::class)
    fun flushIfDirty() {
        if (dirty) {
            save()
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun save() {
        val yaml = YamlConfiguration()
        for ((batchId, players) in records) {
            for ((uuid, record) in players) {
                val path = "batches.$batchId.$uuid"
                yaml["$path.accepted-at"] = record.acceptedAt
                yaml["$path.contract-id"] = record.contractId
            }
        }
        file.parentFile?.mkdirs()
        yaml.save(file)
        dirty = false
    }
}
