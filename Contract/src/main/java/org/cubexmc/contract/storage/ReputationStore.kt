package org.cubexmc.contract.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ParticipantRole
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.logging.Logger

/**
 * Per-player track record, so trust can form between traders without the plugin coding the roles
 * themselves: how many contracts a player completed, walked away from (cancelled), let expire, or
 * took to dispute. Updated at settlement and at cancel/dispute; persisted to `reputation.yml`.
 */
class ReputationStore {
    private val file: File
    private val logger: Logger
    private val records: MutableMap<UUID, Record> = HashMap()
    private var dirty = false

    constructor(plugin: ContractPlugin) : this(File(plugin.dataFolder, "reputation.yml"), plugin.logger)

    constructor(file: File, logger: Logger) {
        this.file = file
        this.logger = logger
    }

    class Record {
        var name: String = ""
        var completed: Int = 0
        var cancelled: Int = 0
        var expired: Int = 0
        var disputed: Int = 0
        var lastActive: Long = 0
    }

    fun isDirty(): Boolean = dirty

    fun snapshot(uuid: UUID?): Record? = if (uuid == null) null else records[uuid]

    fun findByName(name: String): Record? = records.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun recordCancelled(uuid: UUID, name: String?) {
        mutate(uuid, name).cancelled++
    }

    fun recordDisputed(uuid: UUID, name: String?) {
        mutate(uuid, name).disputed++
    }

    fun recordDisputeWithdrawn(uuid: UUID, name: String?) {
        val record = mutate(uuid, name)
        record.disputed = maxOf(0, record.disputed - 1)
    }

    /** Updates each human party when a contract reaches a terminal state. */
    fun recordSettlement(contract: Contract, status: ContractStatus) {
        when (status) {
            ContractStatus.COMPLETED ->
                for (participant in contract.participants()) {
                    val uuid = participant.uuid() ?: continue
                    if (participant.role() == ParticipantRole.MEDIATOR) {
                        continue
                    }
                    mutate(uuid, participant.displayName()).completed++
                }
            ContractStatus.EXPIRED -> {
                val uuid = contract.contractorUuid() ?: return
                mutate(uuid, contract.contractorName()).expired++
            }
            else -> {}
        }
    }

    private fun mutate(uuid: UUID, name: String?): Record {
        val record = records.getOrPut(uuid) { Record() }
        if (!name.isNullOrBlank()) {
            record.name = name
        }
        record.lastActive = System.currentTimeMillis()
        dirty = true
        return record
    }

    fun load() {
        records.clear()
        if (!file.exists()) {
            dirty = false
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (key in yaml.getKeys(false)) {
            val uuid = try {
                UUID.fromString(key)
            } catch (ex: IllegalArgumentException) {
                continue
            }
            val section = yaml.getConfigurationSection(key) ?: continue
            val record = Record()
            record.name = section.getString("name", "") ?: ""
            record.completed = section.getInt("completed")
            record.cancelled = section.getInt("cancelled")
            record.expired = section.getInt("expired")
            record.disputed = section.getInt("disputed")
            record.lastActive = section.getLong("last-active")
            records[uuid] = record
        }
        dirty = false
    }

    fun flushIfDirty() {
        if (dirty) {
            save()
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        for ((uuid, record) in records) {
            val path = uuid.toString()
            yaml.set("$path.name", record.name)
            yaml.set("$path.completed", record.completed)
            yaml.set("$path.cancelled", record.cancelled)
            yaml.set("$path.expired", record.expired)
            yaml.set("$path.disputed", record.disputed)
            yaml.set("$path.last-active", record.lastActive)
        }
        try {
            file.parentFile?.mkdirs()
            yaml.save(file)
            dirty = false
        } catch (ex: IOException) {
            logger.warning("Failed to save reputation: ${ex.message}")
        }
    }
}
