package org.cubexmc.fawereplace

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.block.BlockTypes
import java.io.File
import java.util.Arrays
import java.util.Locale
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.NoOpMigrationStep
import org.cubexmc.config.ResourceFiles
import org.cubexmc.core.CubexPlugin
import org.cubexmc.fawereplace.commands.FaweReplaceCommand
import org.cubexmc.fawereplace.commands.FaweReplaceTabCompleter
import org.cubexmc.fawereplace.metrics.Metrics
import org.cubexmc.fawereplace.tasks.CleaningTask

/**
 * FAWEReplace 主插件类
 * 基于 FastAsyncWorldEdit 的世界方块批量替换与实体清理插件
 *
 * @author cong0707, angushushu
 * @version 1.0.3
 */
class FAWEReplace : CubexPlugin() {
    private lateinit var cleaningTask: CleaningTask
    private var world: World? = null
    private lateinit var languageManager: LanguageManager
    private lateinit var rulesConfig: YamlConfiguration
    private lateinit var rulesFile: File
    private lateinit var resourceFiles: ResourceFiles

    override fun enablePlugin() {
        resourceFiles = ResourceFiles(this)

        // 初始化统计
        Metrics(this, 28977)

        // 保存默认配置
        saveDefaultResources()
        try {
            migrateConfigAndLang()
        } catch (exception: MigrationException) {
            logger.severe("FAWEReplace enable aborted: migration failed. ${exception.message}")
            abortEnable("FAWEReplace migration failed. See logs for details.")
        }
        reloadConfig()

        // 加载/创建 rules.yml
        rulesFile = File(dataFolder, "rules.yml")
        if (!rulesFile.exists()) {
            resourceFiles.saveIfMissing("rules.yml")
            // 首次创建时，尝试从 config.yml 迁移旧数据
            rulesConfig = YamlConfiguration.loadConfiguration(rulesFile)
            migrateDataToRules()
        } else {
            rulesConfig = YamlConfiguration.loadConfiguration(rulesFile)
        }

        // 初始化语言管理器
        val language = config.getString("language", "zh_CN") ?: "zh_CN"
        languageManager = LanguageManager(this, language)

        // 初始化清理任务
        cleaningTask = CleaningTask(logger, dataFolder, languageManager)
        val stopCleaningTask = Runnable {
            if (cleaningTask.isRunning) {
                cleaningTask.stop(null)
            }
        }
        bind(stopCleaningTask)

        // 注册命令 (Paper Plugin API) - 无论配置是否成功都注册命令
        registerCommands()

        // 加载配置并配置任务
        if (!loadConfiguration()) {
            logger.warning(languageManager.getMessage("plugin.config_invalid"))
            logger.warning(languageManager.getMessage("plugin.command_unavailable"))
        } else {
            // 自动启动（仅当配置成功加载时）
            if (config.getBoolean("confirm", false)) {
                Bukkit.getScheduler().runTask(this, Runnable { cleaningTask.start(null) })
            }
        }

        logger.info(languageManager.getMessage("plugin.enabled"))
    }

    override fun disablePlugin() {
        if (::languageManager.isInitialized) {
            logger.info(languageManager.getMessage("plugin.disabled"))
        }
    }

    /**
     * 注册命令 (使用 Paper Plugin API)
     */
    private fun registerCommands() {
        try {
            val fawereplaceCommand: Command = object : Command("fawereplace") {
                private val executor = FaweReplaceCommand(this@FAWEReplace, cleaningTask)
                private val tabCompleter = FaweReplaceTabCompleter()

                override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean =
                    executor.onCommand(sender, this, label, args)

                override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> =
                    tabCompleter.onTabComplete(sender, this, alias, args).toMutableList()
            }

            fawereplaceCommand.description = languageManager.getMessage("help.description")
            fawereplaceCommand.usage = languageManager.getMessage("help.usage")
            fawereplaceCommand.permission = "fawereplace.use"
            fawereplaceCommand.aliases = Arrays.asList("fawerl", "frl")

            server.commandMap.register("fawereplace", fawereplaceCommand)
            logger.info(languageManager.getMessage("plugin.command_registered"))
        } catch (exception: Exception) {
            logger.severe(languageManager.getMessage("plugin.command_register_failed", "error", exception.message))
            exception.printStackTrace()
        }
    }

    /**
     * 获取语言管理器
     */
    fun getLanguageManager(): LanguageManager = languageManager

    /**
     * 重新加载配置（公开方法，供命令调用）
     */
    fun reloadConfiguration(): Boolean {
        saveDefaultResources()
        try {
            migrateConfigAndLang()
        } catch (exception: MigrationException) {
            logger.severe("FAWEReplace reload aborted: migration failed. ${exception.message}")
            return false
        }
        reloadConfig()

        // 重新加载语言
        val language = config.getString("language", "zh_CN") ?: "zh_CN"
        languageManager.reload(language)

        return loadConfiguration()
    }

    /**
     * 检查世界是否已正确配置
     */
    fun isWorldConfigured(): Boolean = world != null

    /**
     * 获取配置的世界名称
     */
    fun getConfiguredWorldName(): String = config.getString("world", "world") ?: "world"

    /**
     * 获取 rules 配置
     */
    fun getRulesConfig(): YamlConfiguration = rulesConfig

    /**
     * 保存 rules 配置
     */
    fun saveRulesConfig() {
        try {
            rulesConfig.save(rulesFile)
        } catch (exception: Exception) {
            logger.severe(languageManager.getMessage("log.rules_save_failed"))
            exception.printStackTrace()
        }
    }

    private fun saveDefaultResources() {
        resourceFiles.saveIfMissing(listOf("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"))
    }

    @Throws(MigrationException::class)
    private fun migrateConfigAndLang() {
        val migrations = MigrationRunner(this)
        migrations.run(
            MigrationPlan.yaml("FAWEReplace config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(NoOpMigrationStep(1, 2, "Add FAWEReplace config-version.")),
        )
        migrateLang(migrations, "zh_CN")
        migrateLang(migrations, "en_US")
    }

    @Throws(MigrationException::class)
    private fun migrateLang(migrations: MigrationRunner, locale: String) {
        migrations.run(
            MigrationPlan.yaml("FAWEReplace lang $locale", "lang/$locale.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(FaweReplaceTextToMiniMessageStep(1, 2)),
        )
    }

    /**
     * 从 config.yml 迁移数据到 rules.yml
     */
    private fun migrateDataToRules() {
        var changed = false

        // 迁移 World
        if (config.contains("world")) {
            rulesConfig.set("world", config.getString("world"))
            changed = true
        }

        // 迁移 Target Region
        if (config.contains("target")) {
            rulesConfig.set("target", config.getConfigurationSection("target"))
            changed = true
        }

        // 迁移 Blocks
        if (config.contains("blocks")) {
            rulesConfig.set("blocks", config.getList("blocks"))
            changed = true
        }

        // 迁移 Entities
        if (config.contains("entities.types")) {
            rulesConfig.set("entities", config.getStringList("entities.types"))
            changed = true
        }

        if (changed) {
            saveRulesConfig()
            logger.info(languageManager.getMessage("log.rules_migrated"))
            logger.info(languageManager.getMessage("log.rules_migrated_note"))
        }
    }

    /**
     * 加载配置并初始化清理任务
     */
    private fun loadConfiguration(): Boolean {
        return try {
            // 读取世界 (优先从 rules.yml 读取)
            val worldName = rulesConfig.getString("world", config.getString("world", "world")) ?: "world"
            val configuredWorld = getWorldFromFaweOrBukkit(worldName)
            world = configuredWorld
            if (configuredWorld == null) {
                logger.severe(languageManager.getMessage("error.world_not_found", "world", worldName))
                return false
            }

            // 读取并行度
            val parallel = config.getInt("parallel", 4)

            // 读取区域大小
            val regionX = config.getInt("region.x", 512)
            var regionY = config.getInt("region.y", 256)
            val regionZ = config.getInt("region.z", 512)
            val regionYFullSpan = !config.contains("region.y")

            // 读取目标范围 (优先从 rules.yml 读取)
            val startX = rulesConfig.getInt("target.start.x", config.getInt("target.start.x", 0))
            val startZ = rulesConfig.getInt("target.start.z", config.getInt("target.start.z", 0))
            val endX = rulesConfig.getInt("target.end.x", config.getInt("target.end.x", 1000))
            val endZ = rulesConfig.getInt("target.end.z", config.getInt("target.end.z", 1000))

            // Y 范围处理
            val startY: Int
            val endY: Int
            val hasRulesY = rulesConfig.contains("target.start.y") && rulesConfig.contains("target.end.y")
            val hasConfigY = config.contains("target.start.y") && config.contains("target.end.y")

            if (!hasRulesY && !hasConfigY) {
                val bukkitWorld = BukkitAdapter.adapt(configuredWorld)
                if (bukkitWorld != null) {
                    startY = bukkitWorld.minHeight
                    endY = bukkitWorld.maxHeight - 1
                } else {
                    startY = -64
                    endY = 319
                }
            } else if (hasRulesY) {
                startY = rulesConfig.getInt("target.start.y")
                endY = rulesConfig.getInt("target.end.y")
            } else {
                startY = config.getInt("target.start.y")
                endY = config.getInt("target.end.y")
            }

            // 如果 region.y 未设置，使用完整 Y 范围
            if (regionYFullSpan) {
                regionY = endY - startY + 1
            }

            // 读取模式配置
            val tiling = config.getBoolean("tiling.enabled", true)
            val fastMode = config.getBoolean("fast-mode", true)
            val progressLogEvery = maxOf(1, config.getInt("progress-log-every", 100))

            // 读取恢复配置
            val resumeEnabled = config.getBoolean("resume.enabled", false)
            val resumeSaveEvery = config.getInt("resume.save-every", 25)
            val resumeFileName = config.getString("resume.file", "progress.yml") ?: "progress.yml"
            val resumeFile = File(dataFolder, resumeFileName)

            // 读取方块替换规则
            val blockRules = buildBlockRulesFromConfig()

            // 读取实体清理配置
            val entityCleanup = config.getBoolean("entities.enabled", false)
            val entityTypes = buildEntityTypesFromConfig()

            // 读取区块跳过配置
            val skipUngeneratedChunks = config.getBoolean("skip-ungenerated-chunks", true)
            val autoFixHeightmap = config.getBoolean("auto-fix-heightmap", true)

            // 读取内存保护配置
            val memoryProtectionEnabled = config.getBoolean("memory-protection.enabled", true)
            val minFreeMemoryPercent = config.getDouble("memory-protection.min-free-memory-percent", 0.20)
            val waitOnLowMemoryMs = config.getLong("memory-protection.wait-on-low-memory-ms", 5000)
            val maxMemoryRetries = config.getInt("memory-protection.max-memory-retries", 10)

            // 读取性能限制配置
            val delayBetweenBatchesMs = config.getLong("performance.delay-between-batches-ms", 100)
            val delayBetweenChunksMs = config.getLong("performance.delay-between-chunks-ms", 20)
            val gcEveryChunks = config.getInt("performance.gc-every-chunks", 50)

            // 配置清理任务
            cleaningTask.configure(
                configuredWorld,
                startX,
                startY,
                startZ,
                endX,
                endY,
                endZ,
                parallel,
                tiling,
                fastMode,
                blockRules,
                entityCleanup,
                entityTypes,
                resumeEnabled,
                resumeSaveEvery,
                resumeFile,
                skipUngeneratedChunks,
                memoryProtectionEnabled,
                minFreeMemoryPercent,
                waitOnLowMemoryMs,
                maxMemoryRetries,
                delayBetweenBatchesMs,
                delayBetweenChunksMs,
                gcEveryChunks,
                progressLogEvery,
                autoFixHeightmap,
            )

            // 设置区域大小
            cleaningTask.setRegionSize(regionX, regionY, regionZ)

            logger.info(
                languageManager.getMessage(
                    "log.config_loaded_info",
                    "world",
                    worldName,
                    "rx",
                    regionX,
                    "ry",
                    regionY,
                    "rz",
                    regionZ,
                    "sx",
                    startX,
                    "sy",
                    startY,
                    "sz",
                    startZ,
                    "ex",
                    endX,
                    "ey",
                    endY,
                    "ez",
                    endZ,
                    "skip",
                    if (skipUngeneratedChunks) languageManager.getMessage("log.on") else languageManager.getMessage("log.off"),
                ),
            )
            logger.info(
                languageManager.getMessage(
                    "log.config_performance_info",
                    "memory",
                    if (memoryProtectionEnabled) {
                        languageManager.getMessage("log.enabled")
                    } else {
                        languageManager.getMessage("log.disabled")
                    },
                    "batch",
                    delayBetweenBatchesMs,
                    "chunk",
                    delayBetweenChunksMs,
                    "gc",
                    gcEveryChunks,
                ),
            )

            true
        } catch (exception: Exception) {
            logger.severe(languageManager.getMessage("error.config_load_failed") + " " + exception.message)
            exception.printStackTrace()
            false
        }
    }

    /**
     * 从配置构建方块替换规则
     */
    private fun buildBlockRulesFromConfig(): Map<com.sk89q.worldedit.world.block.BlockState, Array<BlockType>> {
        val grouped = HashMap<com.sk89q.worldedit.world.block.BlockState, MutableList<BlockType>>()

        // 优先读取 rules.yml
        var list = rulesConfig.getList("blocks")
        if (list == null || list.isEmpty()) {
            list = config.getList("blocks")
        }

        if (list != null) {
            for (item in list) {
                var originName: String? = null
                var targetName: String? = null

                if (item is ConfigurationSection) {
                    originName = item.getString("origin")
                    targetName = item.getString("target")
                } else if (item is Map<*, *>) {
                    val originValue = item["origin"]
                    val targetValue = item["target"]
                    originName = originValue?.toString()
                    targetName = targetValue?.toString()
                }

                if (originName == null || targetName == null) {
                    continue
                }

                val originMaterial = Material.getMaterial(originName.uppercase(Locale.ROOT))
                val targetMaterial = Material.getMaterial(targetName.uppercase(Locale.ROOT))
                if (originMaterial == null || targetMaterial == null) {
                    logger.warning(
                        languageManager.getMessage(
                            "log.invalid_material_config",
                            "origin",
                            originName,
                            "target",
                            targetName,
                        ),
                    )
                    continue
                }

                val originBlock = BlockTypes.get(originMaterial.name.lowercase(Locale.ROOT))
                val targetBlock = BlockTypes.get(targetMaterial.name.lowercase(Locale.ROOT))
                if (originBlock == null || targetBlock == null) {
                    continue
                }

                val targetState = targetBlock.defaultState
                grouped.computeIfAbsent(targetState) { ArrayList() }.add(originBlock)
            }
        }

        // 转换为数组格式
        val compiled = HashMap<com.sk89q.worldedit.world.block.BlockState, Array<BlockType>>()
        for ((targetState, origins) in grouped) {
            if (origins.isNotEmpty()) {
                compiled[targetState] = origins.toTypedArray()
            }
        }

        return compiled
    }

    /**
     * 从配置构建实体类型集合
     */
    private fun buildEntityTypesFromConfig(): Set<EntityType> {
        val types = HashSet<EntityType>()

        // 优先读取 rules.yml
        var entityTypeNames = rulesConfig.getStringList("entities")
        if (entityTypeNames.isEmpty()) {
            entityTypeNames = config.getStringList("entities.types")
        }

        for (value in entityTypeNames) {
            val key = value.trim().uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
            try {
                val entityType = EntityType.valueOf(key)
                types.add(entityType)
            } catch (exception: IllegalArgumentException) {
                logger.warning(languageManager.getMessage("log.unknown_entity_type", "type", value))
            }
        }
        return types
    }

    /**
     * 获取 WorldEdit 世界对象
     */
    private fun getWorldFromFaweOrBukkit(worldName: String): World? {
        try {
            // 尝试使用 FAWE API
            val faweApi = Class.forName("com.fastasyncworldedit.core.FaweAPI")
            val weWorld = faweApi.getMethod("getWorld", String::class.java).invoke(null, worldName)
            if (weWorld is World) {
                return weWorld
            }
        } catch (ignored: Throwable) {
            // FAWE 不可用，使用 Bukkit 适配器
        }

        val bukkitWorld = Bukkit.getWorld(worldName)
        return if (bukkitWorld == null) null else BukkitAdapter.adapt(bukkitWorld)
    }
}
