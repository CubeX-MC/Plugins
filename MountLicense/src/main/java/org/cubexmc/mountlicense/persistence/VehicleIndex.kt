package org.cubexmc.mountlicense.persistence

import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.scheduler.BukkitTask
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.model.VehicleRecord
import org.cubexmc.mountlicense.model.VehicleState
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

class VehicleIndex @JvmOverloads constructor(
    private val plugin: MountLicensePlugin,
    private val file: File = File(plugin.dataFolder, FILE_NAME),
) {
    private val byVehicleId: MutableMap<UUID, VehicleRecord> = ConcurrentHashMap()
    private val entityToVehicle: MutableMap<UUID, UUID> = ConcurrentHashMap()
    private val ownerToVehicles: MutableMap<UUID, MutableList<UUID>> = ConcurrentHashMap()
    private val dirty = AtomicBoolean(false)
    private var autosaveTask: BukkitTask? = null

    fun load() {
        byVehicleId.clear()
        entityToVehicle.clear()
        ownerToVehicles.clear()

        if (!file.exists()) {
            startAutosave()
            return
        }

        val cfg = YamlConfiguration.loadConfiguration(file)
        val root = cfg.getConfigurationSection("vehicles")
        if (root != null) {
            for (key in root.getKeys(false)) {
                val s: ConfigurationSection = root.getConfigurationSection(key) ?: continue
                try {
                    val rec = readRecord(UUID.fromString(key), s)
                    indexInternal(rec)
                } catch (ex: IllegalArgumentException) {
                    plugin.logger.warning("Skipping vehicle entry with bad UUID: $key")
                }
            }
        }

        plugin.logger.info("Loaded ${byVehicleId.size} vehicle record(s).")
        startAutosave()
    }

    private fun readRecord(id: UUID, s: ConfigurationSection): VehicleRecord {
        val entityUuid = UUID.fromString(s.getString("entityUuid", UUID(0, 0).toString()))
        val entityType = s.getString("entityType", "UNKNOWN")
        val ownerUuid = UUID.fromString(s.getString("ownerUuid", UUID(0, 0).toString()))
        val profile = s.getString("profile", "")

        val rec = VehicleRecord(id, entityUuid, entityType, ownerUuid, profile)
        rec.setPlate(s.getString("plate"))
        rec.setDisplayName(s.getString("displayName"))
        rec.setState(VehicleState.fromString(s.getString("state"), VehicleState.ACTIVE))
        rec.setStationId(s.getString("stationId"))

        if (s.contains("world")) {
            rec.setLocation(
                s.getString("world"),
                s.getDouble("x"),
                s.getDouble("y"),
                s.getDouble("z"),
                s.getDouble("yaw").toFloat(),
                s.getDouble("pitch").toFloat(),
            )
        }

        if (s.contains("createdAt")) rec.setCreatedAt(s.getLong("createdAt"))
        if (s.contains("lastSeenAt")) rec.setLastSeenAt(s.getLong("lastSeenAt"))

        for (raw in s.getStringList("trustees")) {
            try {
                rec.addTrustee(UUID.fromString(raw))
            } catch (ex: IllegalArgumentException) {
                // skip malformed trustee uuid
            }
        }
        return rec
    }

    private fun indexInternal(rec: VehicleRecord) {
        byVehicleId[rec.vehicleId()] = rec
        entityToVehicle[rec.entityUuid()] = rec.vehicleId()
        ownerToVehicles.computeIfAbsent(rec.ownerUuid()) { Collections.synchronizedList(ArrayList()) }
            .add(rec.vehicleId())
    }

    @Synchronized
    fun put(rec: VehicleRecord) {
        val existing = byVehicleId[rec.vehicleId()]
        if (existing != null) {
            entityToVehicle.remove(existing.entityUuid())
            val ownerList = ownerToVehicles[existing.ownerUuid()]
            if (ownerList != null) ownerList.remove(existing.vehicleId())
        }
        indexInternal(rec)
        markDirty()
    }

    @Synchronized
    fun remove(vehicleId: UUID): VehicleRecord? {
        val rec = byVehicleId.remove(vehicleId)
        if (rec != null) {
            entityToVehicle.remove(rec.entityUuid())
            val ownerList = ownerToVehicles[rec.ownerUuid()]
            if (ownerList != null) ownerList.remove(vehicleId)
            markDirty()
        }
        return rec
    }

    fun byId(vehicleId: UUID): VehicleRecord? = byVehicleId[vehicleId]

    fun byEntity(entityUuid: UUID): VehicleRecord? {
        val vehicleId = entityToVehicle[entityUuid]
        return if (vehicleId == null) null else byVehicleId[vehicleId]
    }

    fun byOwner(ownerUuid: UUID): List<VehicleRecord> {
        val ids = ownerToVehicles[ownerUuid]
        if (ids == null || ids.isEmpty()) return Collections.emptyList()
        val out = ArrayList<VehicleRecord>(ids.size)
        synchronized(ids) {
            for (id in ids) {
                val rec = byVehicleId[id]
                if (rec != null) out.add(rec)
            }
        }
        return out
    }

    fun all(): Collection<VehicleRecord> = Collections.unmodifiableCollection(byVehicleId.values)

    fun size(): Int = byVehicleId.size

    fun markDirty() {
        dirty.set(true)
    }

    fun flush() {
        if (!dirty.getAndSet(false)) return
        saveBlocking()
    }

    private fun saveBlocking() {
        try {
            if (!plugin.dataFolder.exists() && !plugin.dataFolder.mkdirs()) {
                plugin.logger.warning("Cannot create data folder for vehicle index.")
                return
            }
            val cfg = YamlConfiguration()
            cfg["schema"] = SCHEMA
            val dump = HashMap<String, Map<String, Any?>>()
            for (rec in byVehicleId.values) {
                dump[rec.vehicleId().toString()] = writeRecord(rec)
            }
            cfg.createSection("vehicles", dump)
            cfg.save(file)
        } catch (ex: IOException) {
            plugin.logger.log(Level.SEVERE, "Failed to save vehicles.yml", ex)
            dirty.set(true)
        }
    }

    private fun writeRecord(rec: VehicleRecord): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        m["entityUuid"] = rec.entityUuid().toString()
        m["entityType"] = rec.entityType()
        m["ownerUuid"] = rec.ownerUuid().toString()
        m["profile"] = rec.profile()
        m["plate"] = rec.plate()
        if (rec.displayName() != null) m["displayName"] = rec.displayName()
        m["state"] = rec.state().name
        if (rec.stationId() != null) m["stationId"] = rec.stationId()
        if (rec.world() != null) {
            m["world"] = rec.world()
            m["x"] = rec.x()
            m["y"] = rec.y()
            m["z"] = rec.z()
            m["yaw"] = rec.yaw()
            m["pitch"] = rec.pitch()
        }
        m["createdAt"] = rec.createdAt()
        m["lastSeenAt"] = rec.lastSeenAt()

        if (rec.trustees().isNotEmpty()) {
            val trusteeStrings = ArrayList<String>()
            synchronized(rec.trustees()) {
                for (t in rec.trustees()) {
                    trusteeStrings.add(t.toString())
                }
            }
            m["trustees"] = trusteeStrings
        }
        return m
    }

    private fun startAutosave() {
        val interval = plugin.configManager().getAutosaveIntervalTicks()
        if (interval <= 0) return
        if (autosaveTask != null) autosaveTask?.cancel()
        autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            Runnable {
                if (dirty.getAndSet(false)) {
                    saveBlocking()
                }
            },
            interval.toLong(),
            interval.toLong(),
        )
    }

    private companion object {
        const val SCHEMA: Int = 1
        const val FILE_NAME: String = "vehicles.yml"
    }
}
