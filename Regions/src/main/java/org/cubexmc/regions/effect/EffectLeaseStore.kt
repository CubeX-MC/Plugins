package org.cubexmc.regions.effect

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.model.EffectLease
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.PlayerStateSnapshot
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Logger

internal class EffectLeaseStore(
    dataFolder: File,
    private val logger: Logger,
) {
    private val file = File(dataFolder, "effect-escrow.yml")

    @Synchronized
    fun load(): List<EffectLease> {
        if (!file.exists()) return emptyList()
        val yaml = YamlConfiguration.loadConfiguration(file)
        val version = yaml.getInt("escrow-version", -1)
        require(version == ESCROW_VERSION) {
            "Unsupported effect escrow version $version; expected $ESCROW_VERSION."
        }
        val root = yaml.getConfigurationSection("leases") ?: return emptyList()
        return root.getKeys(false).map { rawId ->
            val id = runCatching { UUID.fromString(rawId) }
                .getOrElse { throw IllegalStateException("Invalid effect escrow lease id '$rawId'.", it) }
            val section = root.getConfigurationSection(rawId)
                ?: throw IllegalStateException("Missing effect escrow section for $rawId.")
            EffectLease(
                id = id,
                playerId = UUID.fromString(section.getString("player") ?: error("Lease $rawId has no player.")),
                regionId = section.getString("region") ?: error("Lease $rawId has no region."),
                effectType = section.getString("effect") ?: error("Lease $rawId has no effect type."),
                scope = runCatching { EffectScope.valueOf(section.getString("scope") ?: "") }
                    .getOrElse { throw IllegalStateException("Lease $rawId has an invalid scope.", it) },
                appliedAtMillis = section.getLong("applied-at"),
                applicationOrder = section.getLong("application-order"),
                expiresAtMillis = section.get("expires-at")?.toString()?.toLongOrNull(),
                snapshot = PlayerStateSnapshot(stringMap(section.getConfigurationSection("snapshot"))),
                metadata = stringMap(section.getConfigurationSection("metadata")),
            )
        }
    }

    @Synchronized
    fun replace(leases: Collection<EffectLease>): Boolean {
        val yaml = YamlConfiguration()
        yaml.set("escrow-version", ESCROW_VERSION)
        for (lease in leases.sortedWith(compareBy<EffectLease> { it.playerId }.thenBy { it.applicationOrder })) {
            val path = "leases.${lease.id}"
            yaml.set("$path.player", lease.playerId.toString())
            yaml.set("$path.region", lease.regionId)
            yaml.set("$path.effect", lease.effectType)
            yaml.set("$path.scope", lease.scope.name)
            yaml.set("$path.applied-at", lease.appliedAtMillis)
            yaml.set("$path.application-order", lease.applicationOrder)
            yaml.set("$path.expires-at", lease.expiresAtMillis)
            yaml.set("$path.snapshot", lease.snapshot.values)
            yaml.set("$path.metadata", lease.metadata)
        }
        return saveAtomically(yaml)
    }

    private fun saveAtomically(yaml: YamlConfiguration): Boolean {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        return try {
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
            true
        } catch (ex: Exception) {
            logger.severe("Failed to persist effect escrow: ${ex.message}")
            false
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun stringMap(section: org.bukkit.configuration.ConfigurationSection?): Map<String, String> =
        section?.getValues(false)?.mapValues { it.value?.toString().orEmpty() }.orEmpty()

    private companion object {
        const val ESCROW_VERSION = 1
    }
}
