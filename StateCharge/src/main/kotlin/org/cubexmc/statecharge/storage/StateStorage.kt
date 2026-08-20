package org.cubexmc.statecharge.storage

import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexLogger
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import org.cubexmc.statecharge.StateChargePlugin

/**
 * 玩家状态开关与计费进度的 YAML 持久化（`states.yml`）。
 *
 * 存三样东西：
 * - **active** 当前开启的状态 id。玩家离线**不清除**——离线不计费，重新上线接着算。
 * - **accrued** 上次结算之后已经开启了多少秒。结算成功即归零；进程被杀最多丢一个 flush 窗口。
 * - **guard** 余额保险阈值：结算后余额低于它就自动关掉全部收费状态。
 *
 * 实现 [Reloadable] 以便作为 reload 链的命名阶段，实现 [Terminable] 让 `bind(this)` 在关停时
 * 自动 flush，不写手写 lambda（Contract 模式）。
 *
 * 写盘：tmp 文件 + 原子 move；save 前把旧文件拷到 `.bak`；load 失败自动回退 `.bak`。
 */
class StateStorage : Reloadable, Terminable {

    private val file: File
    private val backupFile: File
    private val logger: CubexLogger

    private val active: MutableMap<UUID, MutableSet<String>> = LinkedHashMap()
    private val accrued: MutableMap<UUID, MutableMap<String, Long>> = LinkedHashMap()
    private val guards: MutableMap<UUID, BigDecimal> = LinkedHashMap()

    @Volatile
    private var dirty = false

    constructor(plugin: StateChargePlugin) : this(File(plugin.dataFolder, FILE_NAME), plugin.log())

    constructor(file: File, logger: CubexLogger) {
        this.file = file
        this.backupFile = File(file.parentFile, file.name + ".bak")
        this.logger = logger
    }

    override fun reload() {
        close()
        load()
    }

    override fun close() {
        try {
            flushIfDirty()
        } catch (ex: IOException) {
            logger.warn("Failed to flush $FILE_NAME on disable: ${ex.message}")
        }
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
        active.clear()
        accrued.clear()
        guards.clear()
        active.putAll(loaded.active)
        accrued.putAll(loaded.accrued)
        guards.putAll(loaded.guards)
    }

    // ---- 开关 ----

    /** 该玩家当前开启的状态 id（副本，调用方可安全遍历）。 */
    @Synchronized
    fun activeStates(player: UUID): Set<String> = LinkedHashSet(active[player] ?: emptySet())

    @Synchronized
    fun isActive(player: UUID, stateId: String): Boolean = active[player]?.contains(stateId) == true

    @Synchronized
    fun setActive(player: UUID, stateId: String, on: Boolean) {
        if (on) {
            val states = active.getOrPut(player) { LinkedHashSet() }
            if (states.add(stateId)) {
                markDirty()
            }
            return
        }
        val states = active[player] ?: return
        if (states.remove(stateId)) {
            markDirty()
            if (states.isEmpty()) {
                active.remove(player)
            }
        }
    }

    /** 有任何玩家开着状态吗（计费循环用来提前跳过）。 */
    @Synchronized
    fun anyActive(): Boolean = active.isNotEmpty()

    // ---- 已开启但未结算的秒数 ----

    @Synchronized
    fun accruedSeconds(player: UUID, stateId: String): Long = accrued[player]?.get(stateId) ?: 0L

    @Synchronized
    fun accruedFor(player: UUID): Map<String, Long> = LinkedHashMap(accrued[player] ?: emptyMap())

    @Synchronized
    fun addAccrued(player: UUID, stateId: String, seconds: Long) {
        if (seconds <= 0L) {
            return
        }
        val states = accrued.getOrPut(player) { LinkedHashMap() }
        states[stateId] = (states[stateId] ?: 0L) + seconds
        markDirty()
    }

    @Synchronized
    fun clearAccrued(player: UUID, stateId: String) {
        val states = accrued[player] ?: return
        if (states.remove(stateId) != null) {
            markDirty()
            if (states.isEmpty()) {
                accrued.remove(player)
            }
        }
    }

    // ---- 余额保险 ----

    /** 玩家自设的余额下限；null = 用配置里的默认值。 */
    @Synchronized
    fun guard(player: UUID): BigDecimal? = guards[player]

    @Synchronized
    fun setGuard(player: UUID, threshold: BigDecimal?) {
        if (threshold == null) {
            if (guards.remove(player) != null) {
                markDirty()
            }
            return
        }
        if (guards[player] != threshold) {
            guards[player] = threshold
            markDirty()
        }
    }

    // ---- 清理 ----

    @Synchronized
    fun removePlayer(player: UUID) {
        var changed = active.remove(player) != null
        changed = accrued.remove(player) != null || changed
        changed = guards.remove(player) != null || changed
        if (changed) {
            markDirty()
        }
    }

    /** 开启中的状态条数，供日志与测试使用。 */
    @Synchronized
    fun size(): Int = active.values.sumOf { it.size }

    // ---- 读写 ----

    private fun read(): Snapshot {
        if (!file.exists()) {
            return Snapshot()
        }
        try {
            return parse(loadStrict(file))
        } catch (primary: Exception) {
            logger.severe("$FILE_NAME is unreadable: ${primary.message}")
            if (backupFile.exists()) {
                try {
                    val recovered = parse(loadStrict(backupFile))
                    logger.warn("Recovered ${recovered.size()} active states from ${backupFile.name}.")
                    return recovered
                } catch (backup: Exception) {
                    logger.severe("Backup ${backupFile.name} is also unreadable: ${backup.message}")
                }
            }
            return Snapshot()
        }
    }

    @Throws(Exception::class)
    private fun loadStrict(target: File): YamlConfiguration {
        val yaml = YamlConfiguration()
        // 注意:FileConfiguration#load(String) 的参数是"文件路径"而非内容;从字符串解析要用 loadFromString。
        yaml.loadFromString(Files.readString(target.toPath(), StandardCharsets.UTF_8))
        return yaml
    }

    private fun parse(yaml: YamlConfiguration): Snapshot {
        val snapshot = Snapshot()
        val players = yaml.getConfigurationSection("players") ?: return snapshot
        val version = yaml.getInt("storage-version", 1)

        if (version < STORAGE_VERSION) {
            // v1 存的是"预购的剩余时长"。计费模型已改为按实际开启时长收费,两者无法换算——
            // 与其猜一个折算比例,不如明确告知并忽略。
            val stale = players.getKeys(false).size
            if (stale > 0) {
                logger.warn(
                    "$FILE_NAME 是旧版(v$version)格式,存的是预购时长;" +
                        "现模型按实际开启时长计费,$stale 名玩家的旧记录无法换算,已忽略。",
                )
            }
            return snapshot
        }

        for (key in players.getKeys(false)) {
            val uuid = try {
                UUID.fromString(key)
            } catch (ex: IllegalArgumentException) {
                logger.warn("Skipping invalid player id in $FILE_NAME: $key")
                continue
            }
            val section = players.getConfigurationSection(key) ?: continue

            val states = section.getStringList("active").filter { it.isNotBlank() }
            if (states.isNotEmpty()) {
                snapshot.active[uuid] = LinkedHashSet(states)
            }

            section.getConfigurationSection("accrued")?.let { accruedSection ->
                val entries = LinkedHashMap<String, Long>()
                for (stateId in accruedSection.getKeys(false)) {
                    val seconds = accruedSection.getLong(stateId, 0L)
                    if (seconds > 0L) {
                        entries[stateId] = seconds
                    }
                }
                if (entries.isNotEmpty()) {
                    snapshot.accrued[uuid] = entries
                }
            }

            if (section.isSet("guard")) {
                snapshot.guards[uuid] = BigDecimal.valueOf(section.getDouble("guard"))
            }
        }
        return snapshot
    }

    @Throws(IOException::class)
    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("storage-version", STORAGE_VERSION)
        val players = yaml.createSection("players")
        val everyone = LinkedHashSet<UUID>()
        everyone.addAll(active.keys)
        everyone.addAll(accrued.keys)
        everyone.addAll(guards.keys)
        for (uuid in everyone) {
            val section = players.createSection(uuid.toString())
            active[uuid]?.takeIf { it.isNotEmpty() }?.let { section.set("active", it.toList()) }
            accrued[uuid]?.takeIf { it.isNotEmpty() }?.let { entries ->
                val accruedSection = section.createSection("accrued")
                for ((stateId, seconds) in entries) {
                    accruedSection.set(stateId, seconds)
                }
            }
            guards[uuid]?.let { section.set("guard", it.toDouble()) }
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

    private class Snapshot {
        val active: MutableMap<UUID, MutableSet<String>> = LinkedHashMap()
        val accrued: MutableMap<UUID, MutableMap<String, Long>> = LinkedHashMap()
        val guards: MutableMap<UUID, BigDecimal> = LinkedHashMap()

        fun size(): Int = active.values.sumOf { it.size }
    }

    companion object {
        const val FILE_NAME = "states.yml"

        /** v1 = 预购时长模型；v2 = 按实际开启时长计费。 */
        const val STORAGE_VERSION = 2
    }
}
