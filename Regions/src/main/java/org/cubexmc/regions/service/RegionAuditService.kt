package org.cubexmc.regions.service

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.RegionsPlugin
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class RegionAuditEvent(
    val id: UUID = UUID.randomUUID(),
    val regionId: String,
    val actorId: String?,
    val actorName: String,
    val action: String,
    val reason: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val details: Map<String, String> = emptyMap(),
)

class RegionAuditStore(
    private val file: File,
    private val maxEvents: Int = 2_000,
) {
    private val events: MutableList<RegionAuditEvent> = ArrayList()

    @Synchronized
    fun load() {
        events.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (entry in yaml.getMapList("events")) {
            val id = entry["id"]?.toString()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: continue
            val regionId = entry["region"]?.toString() ?: continue
            val action = entry["action"]?.toString() ?: continue
            val details = (entry["details"] as? Map<*, *>)
                ?.entries
                ?.associate { it.key.toString() to it.value.toString() }
                ?: emptyMap()
            events.add(RegionAuditEvent(
                id = id,
                regionId = regionId,
                actorId = entry["actor-id"]?.toString(),
                actorName = entry["actor-name"]?.toString() ?: "system",
                action = action,
                reason = entry["reason"]?.toString(),
                createdAtMillis = entry["created-at"]?.toString()?.toLongOrNull() ?: 0L,
                details = details,
            ))
        }
        trim()
    }

    @Synchronized
    fun append(event: RegionAuditEvent) {
        events.add(event)
        trim()
        save()
    }

    @Synchronized
    fun recent(regionId: String? = null, limit: Int = 20): List<RegionAuditEvent> =
        events.asReversed()
            .asSequence()
            .filter { regionId == null || it.regionId.equals(regionId, ignoreCase = true) }
            .take(limit.coerceIn(1, 100))
            .toList()

    @Synchronized
    fun save() {
        val yaml = YamlConfiguration()
        yaml.set("audit-version", 1)
        yaml.set("events", events.map { event ->
            linkedMapOf<String, Any>(
                "id" to event.id.toString(),
                "region" to event.regionId,
                "actor-name" to event.actorName,
                "action" to event.action,
                "created-at" to event.createdAtMillis,
            ).apply {
                event.actorId?.let { put("actor-id", it) }
                event.reason?.let { put("reason", it) }
                if (event.details.isNotEmpty()) put("details", event.details)
            }
        })
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            yaml.save(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (ex: IOException) {
            throw IllegalStateException("Failed to save Regions audit log: ${ex.message}", ex)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun trim() {
        val overflow = events.size - maxEvents.coerceAtLeast(100)
        if (overflow > 0) events.subList(0, overflow).clear()
    }
}

class RegionAuditService(private val plugin: RegionsPlugin) {
    private val store = RegionAuditStore(
        File(plugin.dataFolder, "audit.yml"),
        plugin.config.getInt("audit.max-events", 2_000),
    )

    fun load() = runSafely("load") { store.load() }

    fun save() = runSafely("save") { store.save() }

    fun record(
        sender: CommandSender?,
        regionId: String,
        action: String,
        reason: String? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        val player = sender as? Player
        runSafely("append") {
            store.append(RegionAuditEvent(
                regionId = regionId,
                actorId = player?.uniqueId?.toString(),
                actorName = sender?.name ?: "system",
                action = action,
                reason = reason,
                details = details,
            ))
        }
    }

    fun recent(regionId: String? = null, limit: Int = 20): List<RegionAuditEvent> =
        store.recent(regionId, limit)

    private fun runSafely(operation: String, block: () -> Unit) {
        runCatching(block).onFailure {
            plugin.logger.severe("Regions audit $operation failed: ${it.message}")
        }
    }
}
