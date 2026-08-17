package org.cubexmc.contract.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.integration.reputation.ReputationDeltaSink
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ParticipantRole
import java.io.File
import java.io.IOException
import java.util.UUID
import org.cubexmc.core.CubexLogger

/**
 * Per-player track record, so trust can form between traders without the plugin coding the roles
 * themselves: how many contracts a player completed, walked away from (cancelled), let expire, or
 * took to dispute. Updated at settlement and at cancel/dispute; persisted to `reputation.yml`.
 */
/**
 * Implements [Reloadable] so it can be a named stage in the plugin's reload chain, and
 * [Terminable] so `bind(this)` flushes it on disable without a hand-written lambda.
 */
class ReputationStore : Reloadable, Terminable {
    private val file: File
    private val logger: CubexLogger
    private val deltaSink: ReputationDeltaSink
    private val records: MutableMap<UUID, Record> = HashMap()
    private var dirty = false

    constructor(plugin: ContractPlugin) : this(plugin, ReputationDeltaSink.NONE)

    constructor(plugin: ContractPlugin, deltaSink: ReputationDeltaSink) :
        this(File(plugin.dataFolder, "reputation.yml"), plugin.log(), deltaSink)

    constructor(file: File, logger: CubexLogger) : this(file, logger, ReputationDeltaSink.NONE)

    constructor(file: File, logger: CubexLogger, deltaSink: ReputationDeltaSink) {
        this.file = file
        this.logger = logger
        this.deltaSink = deltaSink
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
        mirror(uuid, "cancelled", 1.0)
    }

    fun recordDisputed(uuid: UUID, name: String?) {
        mutate(uuid, name).disputed++
        mirror(uuid, "disputed", 1.0)
    }

    fun recordDisputeWithdrawn(uuid: UUID, name: String?) {
        val record = mutate(uuid, name)
        if (record.disputed > 0) {
            record.disputed--
            mirror(uuid, "disputed", -1.0)
        }
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
                    mirror(uuid, "completed", 1.0)
                }
            ContractStatus.EXPIRED -> {
                val uuid = contract.contractorUuid() ?: return
                mutate(uuid, contract.contractorName()).expired++
                mirror(uuid, "expired", 1.0)
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

    private fun mirror(uuid: UUID, fieldId: String, delta: Double) {
        try {
            deltaSink.add(uuid, fieldId, delta)
        } catch (ex: Exception) {
            logger.warn("Optional reputation mirror failed; the local Contract record was preserved.", ex)
        } catch (ex: LinkageError) {
            logger.warn("Optional reputation mirror became incompatible; the local Contract record was preserved.", ex)
        }
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
            logger.warn("Failed to save reputation: ${ex.message}")
        }
    }

    /** Reload stage: re-read the backing file. */
    override fun reload() {
        load()
    }

    /** Terminable: flush pending writes when the plugin shuts down. */
    override fun close() {
        flushIfDirty()
    }

}
