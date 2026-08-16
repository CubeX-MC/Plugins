package org.cubexmc.statecharge.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexLogger
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import org.cubexmc.statecharge.StateChargePlugin
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 玩家剩余状态时长的 YAML 持久化(`states.yml`)。
 *
 * 实现 [Reloadable] 以便作为 reload 链的命名阶段,实现 [Terminable] 让 `bind(this)` 在
 * 关停时自动 flush,不写手写 lambda(Contract 模式)。
 *
 * 写盘:tmp 文件 + 原子 move;save 前把旧文件拷到 `.bak`;load 失败自动回退 .bak。
 */
class StateStorage : Reloadable, Terminable {

    private val file: File
    private val backupFile: File
    private val logger: CubexLogger
    private val remaining: MutableMap<UUID, MutableMap<String, Long>> = LinkedHashMap()

    @Volatile
    private var dirty = false

    constructor(plugin: StateChargePlugin) : this(File(plugin.dataFolder, "states.yml"), plugin.log())

    constructor(file: File, logger: CubexLogger) {
        this.file = file
        this.backupFile = File(file.parentFile, file.name + ".bak")
        this.logger = logger
    }

    @Synchronized
    fun markDirty() {
        dirty = true
    }

    @Synchronized
    fun isDirty(): Boolean = dirty

    @Throws(IOException::class)
    @Synchronized
    fun flushIfDirty() {
        if (!dirty) {
            return
        }
        save()
    }

    @Synchronized
    fun load() {
        val loaded = read()
        remaining.clear()
        remaining.putAll(loaded)
    }

    @Synchronized
    fun remaining(player: UUID, stateId: String): Long = remaining[player]?.get(stateId) ?: 0L

    /** 该玩家全部生效状态的副本(调用方可安全遍历/修改)。 */
    @Synchronized
    fun active(player: UUID): Map<String, Long> {
        val states = remaining[player] ?: return emptyMap()
        return LinkedHashMap(states)
    }

    @Synchronized
    fun setRemaining(player: UUID, stateId: String, seconds: Long) {
        if (seconds <= 0) {
            removeState(player, stateId)
            return
        }
        val states = remaining.getOrPut(player) { LinkedHashMap() }
        if (states[stateId] != seconds) {
            states[stateId] = seconds
            markDirty()
        }
    }

    @Synchronized
    fun addSeconds(player: UUID, stateId: String, seconds: Long) {
        if (seconds <= 0) {
            return
        }
        setRemaining(player, stateId, remaining(player, stateId) + seconds)
    }

    @Synchronized
    fun removeState(player: UUID, stateId: String) {
        val states = remaining[player] ?: return
        if (states.remove(stateId) != null) {
            markDirty()
            if (states.isEmpty()) {
                remaining.remove(player)
            }
        }
    }

    @Synchronized
    fun removePlayer(player: UUID) {
        if (remaining.remove(player) != null) {
            markDirty()
        }
    }

    /** 全部生效条目数(玩家 × 状态),供日志使用。 */
    @Synchronized
    fun size(): Int = remaining.values.sumOf { it.size }

    override fun reload() {
        load()
    }

    override fun close() {
        try {
            flushIfDirty()
        } catch (ex: IOException) {
            logger.warn("Failed to flush states.yml on disable: ${ex.message}")
        }
    }

    // ---- 读写 ----

    private fun read(): Map<UUID, MutableMap<String, Long>> {
        if (!file.exists()) {
            return LinkedHashMap()
        }
        try {
            return parse(loadStrict(file))
        } catch (primary: Exception) {
            logger.severe("states.yml is unreadable: ${primary.message}")
            if (backupFile.exists()) {
                try {
                    val recovered = parse(loadStrict(backupFile))
                    logger.warn("Recovered ${recovered.values.sumOf { it.size }} state entries from ${backupFile.name}.")
                    return recovered
                } catch (backup: Exception) {
                    logger.severe("Backup ${backupFile.name} is also unreadable: ${backup.message}")
                }
            }
            return LinkedHashMap()
        }
    }

    private fun parse(yaml: YamlConfiguration): Map<UUID, MutableMap<String, Long>> {
        val result = LinkedHashMap<UUID, MutableMap<String, Long>>()
        val players = yaml.getConfigurationSection("players") ?: return result
        for (uuidKey in players.getKeys(false)) {
            val uuid = try {
                UUID.fromString(uuidKey)
            } catch (ex: IllegalArgumentException) {
                logger.warn("Skipping invalid player id in states.yml: $uuidKey")
                continue
            }
            val section = players.getConfigurationSection(uuidKey) ?: continue
            val states = LinkedHashMap<String, Long>()
            for (stateId in section.getKeys(false)) {
                val value = section.get(stateId)
                if (value is Number && value.toLong() > 0) {
                    states[stateId] = value.toLong()
                }
            }
            if (states.isNotEmpty()) {
                result[uuid] = states
            }
        }
        return result
    }

    @Throws(IOException::class)
    private fun save() {
        val yaml = YamlConfiguration()
        val players = yaml.createSection("players")
        for ((uuid, states) in remaining) {
            val section = players.createSection(uuid.toString())
            for ((stateId, seconds) in states) {
                section.set(stateId, seconds)
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        Files.writeString(tmp.toPath(), yaml.saveToString(), StandardCharsets.UTF_8)
        if (file.exists()) {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (ex: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        dirty = false
    }

    @Throws(Exception::class)
    private fun loadStrict(target: File): YamlConfiguration {
        val yaml = YamlConfiguration()
        // 注意:FileConfiguration#load(String) 的参数是"文件路径"而非内容;从字符串解析要用 loadFromString。
        yaml.loadFromString(Files.readString(target.toPath(), StandardCharsets.UTF_8))
        return yaml
    }
}
