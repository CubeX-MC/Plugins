package org.cubexmc.fawereplace;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.cubexmc.fawereplace.commands.FaweReplaceCommand;
import org.cubexmc.fawereplace.commands.FaweReplaceTabCompleter;
import org.cubexmc.fawereplace.metrics.Metrics;
import org.cubexmc.fawereplace.tasks.CleaningTask;
import org.cubexmc.config.MigrationException;
import org.cubexmc.config.MigrationPlan;
import org.cubexmc.config.MigrationRunner;
import org.cubexmc.config.NoOpMigrationStep;
import org.cubexmc.config.ResourceFiles;
import org.cubexmc.core.CubexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.util.*;

/**
 * FAWEReplace 主插件类
 * 基于 FastAsyncWorldEdit 的世界方块批量替换与实体清理插件
 * 
 * @author cong0707, angushushu
 * @version 1.0.3
 */
public final class FAWEReplace extends CubexPlugin {

    private CleaningTask cleaningTask;
    private World world;
    private LanguageManager languageManager;
    private org.bukkit.configuration.file.YamlConfiguration rulesConfig;
    private File rulesFile;
    private ResourceFiles resourceFiles;

    @Override
    protected void enablePlugin() {
        this.resourceFiles = new ResourceFiles(this);

        // 初始化统计
        Metrics metrics = new Metrics(this, 28977);

        // 保存默认配置
        saveDefaultResources();
        try {
            migrateConfigAndLang();
        } catch (MigrationException ex) {
            getLogger().severe("FAWEReplace enable aborted: migration failed. " + ex.getMessage());
            abortEnable("FAWEReplace migration failed. See logs for details.");
        }
        reloadConfig();

        // 加载/创建 rules.yml
        rulesFile = new File(getDataFolder(), "rules.yml");
        if (!rulesFile.exists()) {
            resourceFiles.saveIfMissing("rules.yml");
            // 首次创建时，尝试从 config.yml 迁移旧数据
            rulesConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(rulesFile);
            migrateDataToRules();
        } else {
            rulesConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(rulesFile);
        }

        // 初始化语言管理器
        String language = getConfig().getString("language", "zh_CN");
        languageManager = new LanguageManager(this, language);

        // 初始化清理任务
        cleaningTask = new CleaningTask(getLogger(), getDataFolder(), languageManager);
        Runnable stopCleaningTask = () -> {
            if (cleaningTask != null && cleaningTask.isRunning()) {
                cleaningTask.stop(null);
            }
        };
        bind(stopCleaningTask);

        // 注册命令 (Paper Plugin API) - 无论配置是否成功都注册命令
        registerCommands();

        // 加载配置并配置任务
        if (!loadConfiguration()) {
            getLogger().warning(languageManager.getMessage("plugin.config_invalid"));
            getLogger().warning(languageManager.getMessage("plugin.command_unavailable"));
        } else {
            // 自动启动（仅当配置成功加载时）
            if (getConfig().getBoolean("confirm", false)) {
                Bukkit.getScheduler().runTask(this, () -> cleaningTask.start(null));
            }
        }

        getLogger().info(languageManager.getMessage("plugin.enabled"));
    }

    @Override
    protected void disablePlugin() {
        getLogger().info(languageManager.getMessage("plugin.disabled"));
    }

    /**
     * 注册命令 (使用 Paper Plugin API)
     */
    private void registerCommands() {
        try {
            org.bukkit.command.Command fawereplaceCmd = new org.bukkit.command.Command("fawereplace") {
                private final FaweReplaceCommand executor = new FaweReplaceCommand(FAWEReplace.this, cleaningTask);
                private final FaweReplaceTabCompleter tabCompleter = new FaweReplaceTabCompleter();

                @Override
                public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                    return executor.onCommand(sender, this, label, args);
                }

                @Override
                public List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
                    return tabCompleter.onTabComplete(sender, this, alias, args);
                }
            };

            fawereplaceCmd.setDescription(languageManager.getMessage("help.description"));
            fawereplaceCmd.setUsage(languageManager.getMessage("help.usage"));
            fawereplaceCmd.setPermission("fawereplace.use");
            fawereplaceCmd.setAliases(Arrays.asList("fawerl", "frl"));

            this.getServer().getCommandMap().register("fawereplace", fawereplaceCmd);
            getLogger().info(languageManager.getMessage("plugin.command_registered"));
        } catch (Exception e) {
            getLogger().severe(languageManager.getMessage("plugin.command_register_failed", "error", e.getMessage()));
            e.printStackTrace();
        }
    }

    /**
     * 获取语言管理器
     */
    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    /**
     * 重新加载配置（公开方法，供命令调用）
     */
    public boolean reloadConfiguration() {
        saveDefaultResources();
        try {
            migrateConfigAndLang();
        } catch (MigrationException ex) {
            getLogger().severe("FAWEReplace reload aborted: migration failed. " + ex.getMessage());
            return false;
        }
        reloadConfig();

        // 重新加载语言
        String language = getConfig().getString("language", "zh_CN");
        languageManager.reload(language);

        return loadConfiguration();
    }

    /**
     * 检查世界是否已正确配置
     */
    public boolean isWorldConfigured() {
        return world != null;
    }

    /**
     * 获取配置的世界名称
     */
    public String getConfiguredWorldName() {
        return getConfig().getString("world", "world");
    }

    /**
     * 获取 rules 配置
     */
    public org.bukkit.configuration.file.YamlConfiguration getRulesConfig() {
        return rulesConfig;
    }

    /**
     * 保存 rules 配置
     */
    public void saveRulesConfig() {
        try {
            rulesConfig.save(rulesFile);
        } catch (Exception e) {
            getLogger().severe(languageManager.getMessage("log.rules_save_failed"));
            e.printStackTrace();
        }
    }

    private void saveDefaultResources() {
        resourceFiles.saveIfMissing(java.util.List.of("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"));
    }

    private void migrateConfigAndLang() throws MigrationException {
        MigrationRunner migrations = new MigrationRunner(this);
        migrations.run(MigrationPlan.yaml("FAWEReplace config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(new NoOpMigrationStep(1, 2, "Add FAWEReplace config-version.")));
        migrateLang(migrations, "zh_CN");
        migrateLang(migrations, "en_US");
    }

    private void migrateLang(MigrationRunner migrations, String locale) throws MigrationException {
        migrations.run(MigrationPlan.yaml("FAWEReplace lang " + locale, "lang/" + locale + ".yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(new FaweReplaceTextToMiniMessageStep(1, 2)));
    }

    /**
     * 从 config.yml 迁移数据到 rules.yml
     */
    private void migrateDataToRules() {
        boolean changed = false;

        // 迁移 World
        if (getConfig().contains("world")) {
            rulesConfig.set("world", getConfig().getString("world"));
            changed = true;
        }

        // 迁移 Target Region
        if (getConfig().contains("target")) {
            rulesConfig.set("target", getConfig().getConfigurationSection("target"));
            changed = true;
        }

        // 迁移 Blocks
        if (getConfig().contains("blocks")) {
            rulesConfig.set("blocks", getConfig().getList("blocks"));
            changed = true;
        }

        // 迁移 Entities
        if (getConfig().contains("entities.types")) {
            rulesConfig.set("entities", getConfig().getStringList("entities.types"));
            changed = true;
        }

        if (changed) {
            saveRulesConfig();
            getLogger().info(languageManager.getMessage("log.rules_migrated"));
            getLogger().info(languageManager.getMessage("log.rules_migrated_note"));
        }
    }

    /**
     * 加载配置并初始化清理任务
     */
    private boolean loadConfiguration() {
        try {
            // 读取世界 (优先从 rules.yml 读取)
            String worldName = rulesConfig.getString("world", getConfig().getString("world", "world"));
            world = getWorldFromFaweOrBukkit(worldName);
            if (world == null) {
                getLogger().severe(languageManager.getMessage("error.world_not_found", "world", worldName));
                return false;
            }

            // 读取并行度
            int parallel = getConfig().getInt("parallel", 4);

            // 读取区域大小
            int regionX = getConfig().getInt("region.x", 512);
            int regionZ = getConfig().getInt("region.z", 512);
            boolean regionYFullSpan = !getConfig().contains("region.y");
            int regionY = regionYFullSpan ? 0 : getConfig().getInt("region.y", 256);

            // 读取目标范围 (优先从 rules.yml 读取)
            int startX = rulesConfig.getInt("target.start.x", getConfig().getInt("target.start.x", 0));
            int startZ = rulesConfig.getInt("target.start.z", getConfig().getInt("target.start.z", 0));
            int endX = rulesConfig.getInt("target.end.x", getConfig().getInt("target.end.x", 1000));
            int endZ = rulesConfig.getInt("target.end.z", getConfig().getInt("target.end.z", 1000));

            // Y 范围处理
            int startY, endY;
            boolean hasRulesY = rulesConfig.contains("target.start.y") && rulesConfig.contains("target.end.y");
            boolean hasConfigY = getConfig().contains("target.start.y") && getConfig().contains("target.end.y");

            if (!hasRulesY && !hasConfigY) {
                org.bukkit.World bw = BukkitAdapter.adapt(world);
                if (bw != null) {
                    startY = bw.getMinHeight();
                    endY = bw.getMaxHeight() - 1;
                } else {
                    startY = -64;
                    endY = 319;
                }
            } else if (hasRulesY) {
                startY = rulesConfig.getInt("target.start.y");
                endY = rulesConfig.getInt("target.end.y");
            } else {
                startY = getConfig().getInt("target.start.y");
                endY = getConfig().getInt("target.end.y");
            }

            // 如果 region.y 未设置，使用完整 Y 范围
            if (regionYFullSpan) {
                regionY = endY - startY + 1;
            }

            // 读取模式配置
            boolean tiling = getConfig().getBoolean("tiling.enabled", true);
            boolean fastMode = getConfig().getBoolean("fast-mode", true);
            int progressLogEvery = Math.max(1, getConfig().getInt("progress-log-every", 100));

            // 读取恢复配置
            boolean resumeEnabled = getConfig().getBoolean("resume.enabled", false);
            int resumeSaveEvery = getConfig().getInt("resume.save-every", 25);
            String resumeFileName = getConfig().getString("resume.file", "progress.yml");
            File resumeFile = new File(getDataFolder(), resumeFileName);

            // 读取方块替换规则
            Map<com.sk89q.worldedit.world.block.BlockState, BlockType[]> blockRules = buildBlockRulesFromConfig();

            // 读取实体清理配置
            boolean entityCleanup = getConfig().getBoolean("entities.enabled", false);
            Set<EntityType> entityTypes = buildEntityTypesFromConfig();

            // 读取区块跳过配置
            boolean skipUngeneratedChunks = getConfig().getBoolean("skip-ungenerated-chunks", true);
            boolean autoFixHeightmap = getConfig().getBoolean("auto-fix-heightmap", true);

            // 读取内存保护配置
            boolean memoryProtectionEnabled = getConfig().getBoolean("memory-protection.enabled", true);
            double minFreeMemoryPercent = getConfig().getDouble("memory-protection.min-free-memory-percent", 0.20);
            long waitOnLowMemoryMs = getConfig().getLong("memory-protection.wait-on-low-memory-ms", 5000);
            int maxMemoryRetries = getConfig().getInt("memory-protection.max-memory-retries", 10);

            // 读取性能限制配置
            long delayBetweenBatchesMs = getConfig().getLong("performance.delay-between-batches-ms", 100);
            long delayBetweenChunksMs = getConfig().getLong("performance.delay-between-chunks-ms", 20);
            int gcEveryChunks = getConfig().getInt("performance.gc-every-chunks", 50);

            // 配置清理任务
            cleaningTask.configure(world, startX, startY, startZ, endX, endY, endZ,
                    parallel, tiling, fastMode, blockRules, entityCleanup, entityTypes,
                    resumeEnabled, resumeSaveEvery, resumeFile, skipUngeneratedChunks,
                    memoryProtectionEnabled, minFreeMemoryPercent, waitOnLowMemoryMs, maxMemoryRetries,
                    delayBetweenBatchesMs, delayBetweenChunksMs, gcEveryChunks,
                    progressLogEvery, autoFixHeightmap);

            // 设置区域大小
            cleaningTask.setRegionSize(regionX, regionY, regionZ);

            getLogger().info(languageManager.getMessage("log.config_loaded_info",
                    "world", worldName, "rx", regionX, "ry", regionY, "rz", regionZ,
                    "sx", startX, "sy", startY, "sz", startZ, "ex", endX, "ey", endY, "ez", endZ,
                    "skip", skipUngeneratedChunks ? languageManager.getMessage("log.on")
                            : languageManager.getMessage("log.off")));
            getLogger().info(languageManager.getMessage("log.config_performance_info",
                    "memory",
                    memoryProtectionEnabled ? languageManager.getMessage("log.enabled")
                            : languageManager.getMessage("log.disabled"),
                    "batch", delayBetweenBatchesMs, "chunk", delayBetweenChunksMs, "gc", gcEveryChunks));

            return true;
        } catch (Exception e) {
            getLogger().severe(languageManager.getMessage("error.config_load_failed") + " " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从配置构建方块替换规则
     */
    private Map<com.sk89q.worldedit.world.block.BlockState, BlockType[]> buildBlockRulesFromConfig() {
        Map<com.sk89q.worldedit.world.block.BlockState, List<BlockType>> grouped = new HashMap<>();

        // 优先读取 rules.yml
        List<?> list = rulesConfig.getList("blocks");
        if (list == null || list.isEmpty()) {
            list = getConfig().getList("blocks");
        }

        if (list != null) {
            for (Object o : list) {
                String originName = null;
                String targetName = null;

                if (o instanceof org.bukkit.configuration.ConfigurationSection) {
                    org.bukkit.configuration.ConfigurationSection sec = (org.bukkit.configuration.ConfigurationSection) o;
                    originName = sec.getString("origin");
                    targetName = sec.getString("target");
                } else if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) o;
                    Object ov = map.get("origin");
                    Object tv = map.get("target");
                    originName = ov == null ? null : ov.toString();
                    targetName = tv == null ? null : tv.toString();
                }

                if (originName == null || targetName == null)
                    continue;

                Material om = Material.getMaterial(originName.toUpperCase(Locale.ROOT));
                Material tm = Material.getMaterial(targetName.toUpperCase(Locale.ROOT));
                if (om == null || tm == null) {
                    getLogger().warning(languageManager.getMessage("log.invalid_material_config", "origin", originName,
                            "target", targetName));
                    continue;
                }

                BlockType ob = BlockTypes.get(om.name().toLowerCase(Locale.ROOT));
                BlockType tb = BlockTypes.get(tm.name().toLowerCase(Locale.ROOT));
                if (ob == null || tb == null)
                    continue;

                com.sk89q.worldedit.world.block.BlockState targetState = tb.getDefaultState();
                grouped.computeIfAbsent(targetState, k -> new ArrayList<>()).add(ob);
            }
        }

        // 转换为数组格式
        Map<com.sk89q.worldedit.world.block.BlockState, BlockType[]> compiled = new HashMap<>();
        for (Map.Entry<com.sk89q.worldedit.world.block.BlockState, List<BlockType>> entry : grouped.entrySet()) {
            List<BlockType> origins = entry.getValue();
            if (!origins.isEmpty()) {
                compiled.put(entry.getKey(), origins.toArray(new BlockType[0]));
            }
        }

        return compiled;
    }

    /**
     * 从配置构建实体类型集合
     */
    private Set<EntityType> buildEntityTypesFromConfig() {
        Set<EntityType> types = new HashSet<>();

        // 优先读取 rules.yml
        List<String> ets = rulesConfig.getStringList("entities");
        if (ets == null || ets.isEmpty()) {
            ets = getConfig().getStringList("entities.types");
        }

        if (ets != null) {
            for (String s : ets) {
                if (s == null)
                    continue;
                String key = s.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
                try {
                    EntityType et = EntityType.valueOf(key);
                    types.add(et);
                } catch (IllegalArgumentException ex) {
                    getLogger().warning(languageManager.getMessage("log.unknown_entity_type", "type", s));
                }
            }
        }
        return types;
    }

    /**
     * 获取 WorldEdit 世界对象
     */
    private World getWorldFromFaweOrBukkit(String worldName) {
        try {
            // 尝试使用 FAWE API
            Class<?> faweApi = Class.forName("com.fastasyncworldedit.core.FaweAPI");
            Object weWorld = faweApi.getMethod("getWorld", String.class).invoke(null, worldName);
            if (weWorld instanceof World) {
                return (World) weWorld;
            }
        } catch (Throwable ignored) {
            // FAWE 不可用，使用 Bukkit 适配器
        }

        org.bukkit.World bw = Bukkit.getWorld(worldName);
        return bw == null ? null : BukkitAdapter.adapt(bw);
    }
}
