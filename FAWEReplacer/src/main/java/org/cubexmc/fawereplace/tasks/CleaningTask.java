package org.cubexmc.fawereplace.tasks;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.BlockTypeMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.BlockPattern;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockType;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.fawereplace.LanguageManager;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.Barrel;
import org.bukkit.block.Beacon;
import org.bukkit.block.Beehive;
import org.bukkit.block.Banner;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Campfire;
import org.bukkit.block.Chest;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Conduit;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.block.EnchantingTable;
import org.bukkit.block.Furnace;
import org.bukkit.block.Hopper;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 清理任务的核心逻辑封装
 */
public class CleaningTask {

    private final Logger logger;
    private final File dataFolder;

    // 任务状态
    private volatile boolean running = false;
    private ExecutorService executor;

    // 统计数据
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicLong totalBlocksReplaced = new AtomicLong(0);
    private final AtomicLong totalEntitiesRemoved = new AtomicLong(0);

    // 任务配置
    private World world;
    private int taskStartX, taskStartY, taskStartZ;
    private int taskEndX, taskEndY, taskEndZ;
    private int parallel;
    private boolean tiling;
    private boolean fastMode;
    private boolean resumeEnabled;
    private int resumeSaveEvery;
    private File resumeFile;

    // 分块配置
    private final AtomicLong nextTileIndex = new AtomicLong(0);
    private long tilesX, tilesY, tilesZ, totalTileCount;
    private int activeRegionX, activeRegionY, activeRegionZ;

    // 方块规则
    private Map<com.sk89q.worldedit.world.block.BlockState, BlockType[]> groupedBlockRules;

    // 实体清理
    private boolean entityCleanupEnabled;
    private Set<org.bukkit.entity.EntityType> entityTypes;

    // 区块检查
    private boolean skipUngeneratedChunks;
    private final AtomicLong skippedChunks = new AtomicLong(0);

    // 内存保护
    private boolean memoryProtectionEnabled;
    private double minFreeMemoryPercent;
    private long waitOnLowMemoryMs;
    private int maxMemoryRetries;

    // 性能限制
    private long delayBetweenBatchesMs;
    private long delayBetweenChunksMs;
    private int gcEveryChunks;
    private int progressLogEvery = 100;
    private boolean autoFixHeightmap = true;

    // 日志锁
    private final Object logLock = new Object();
    private final Object progressLock = new Object();

    // 语言管理器
    private LanguageManager lang;

    public CleaningTask(Logger logger, File dataFolder, LanguageManager languageManager) {
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.lang = languageManager;
    }

    /**
     * 检查任务是否正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 配置任务参数
     */
    public void configure(World world, int startX, int startY, int startZ, int endX, int endY, int endZ,
            int parallel, boolean tiling, boolean fastMode,
            Map<com.sk89q.worldedit.world.block.BlockState, BlockType[]> blockRules,
            boolean entityCleanup, Set<org.bukkit.entity.EntityType> entities,
            boolean resumeEnabled, int resumeSaveEvery, File resumeFile,
            boolean skipUngeneratedChunks,
            boolean memoryProtection, double minFreeMemory, long waitOnLowMemory, int maxRetries,
            long delayBetweenBatches, long delayBetweenChunks, int gcEvery,
            int progressLogEvery, boolean autoFixHeightmap) {
        this.world = world;
        this.taskStartX = Math.min(startX, endX);
        this.taskStartY = Math.min(startY, endY);
        this.taskStartZ = Math.min(startZ, endZ);
        this.taskEndX = Math.max(startX, endX);
        this.taskEndY = Math.max(startY, endY);
        this.taskEndZ = Math.max(startZ, endZ);
        this.parallel = parallel;
        this.tiling = tiling;
        this.fastMode = fastMode;
        this.groupedBlockRules = blockRules;
        this.entityCleanupEnabled = entityCleanup;
        this.entityTypes = entities;
        this.resumeEnabled = resumeEnabled;
        this.resumeSaveEvery = resumeSaveEvery;
        this.resumeFile = resumeFile;
        this.skipUngeneratedChunks = skipUngeneratedChunks;
        this.skippedChunks.set(0);

        // 内存保护和性能配置
        this.memoryProtectionEnabled = memoryProtection;
        this.minFreeMemoryPercent = minFreeMemory;
        this.waitOnLowMemoryMs = waitOnLowMemory;
        this.maxMemoryRetries = maxRetries;
        this.delayBetweenBatchesMs = delayBetweenBatches;
        this.delayBetweenChunksMs = delayBetweenChunks;
        this.gcEveryChunks = gcEvery;
        this.progressLogEvery = Math.max(1, progressLogEvery);
        this.autoFixHeightmap = autoFixHeightmap;
    }

    /**
     * 设置分块大小
     */
    public void setRegionSize(int regionX, int regionY, int regionZ) {
        this.activeRegionX = regionX;
        this.activeRegionY = regionY;
        this.activeRegionZ = regionZ;

        // 计算分块数量
        tilesX = Math.max(1L, ceilDiv((long) taskEndX - (long) taskStartX + 1L, regionX));
        tilesY = Math.max(1L, ceilDiv((long) taskEndY - (long) taskStartY + 1L, regionY));
        tilesZ = Math.max(1L, ceilDiv((long) taskEndZ - (long) taskStartZ + 1L, regionZ));
        totalTileCount = tilesX * tilesY * tilesZ;
    }

    /**
     * 开始清理任务
     * 
     * @param invoker 命令发送者
     */
    public void start(CommandSender invoker) {
        start(invoker, false);
    }

    /**
     * 开始清理任务
     * 
     * @param invoker      命令发送者
     * @param forceRestart 是否强制重新开始（忽略之前的进度）
     */
    public void start(CommandSender invoker, boolean forceRestart) {
        if (running) {
            if (invoker != null)
                invoker.sendMessage(lang.getMessage("start.already_running"));
            return;
        }

        processed.set(0);
        totalBlocksReplaced.set(0);
        totalEntitiesRemoved.set(0);
        nextTileIndex.set(0L);

        // 尝试加载进度（除非强制重新开始）
        boolean resumed = false;
        if (resumeEnabled && tiling && !forceRestart) {
            resumed = loadProgressIfAvailable();
            if (resumed && invoker != null) {
                invoker.sendMessage(
                        lang.getMessage("start.resume_found", "progress", processed.get() + "/" + total.get()));
                invoker.sendMessage(lang.getMessage("start.resume_use_fresh"));
            }
        } else if (forceRestart) {
            // 如果强制重新开始，删除进度文件
            clearProgressFile();
            if (invoker != null) {
                invoker.sendMessage(lang.getMessage("start.starting_fresh"));
            }
        }

        total.set((int) Math.min(totalTileCount, Integer.MAX_VALUE));

        logToFile("START",
                String.format(
                        "world=%s, region=%dx%dx%d, area=(%d,%d,%d)->(%d,%d,%d), parallel=%d, tiling=%s, resume=%s",
                        world.getName(), activeRegionX, activeRegionY, activeRegionZ,
                        taskStartX, taskStartY, taskStartZ, taskEndX, taskEndY, taskEndZ,
                        parallel, tiling, resumed ? "resumed" : "fresh"));

        running = true;

        if (!tiling) {
            // 单区域模式
            Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
                long changed = processRegion(
                        BlockVector3.at(taskStartX, taskStartY, taskStartZ),
                        BlockVector3.at(taskEndX, taskEndY, taskEndZ),
                        fastMode);
                if (changed >= 0)
                    totalBlocksReplaced.addAndGet(changed);
                running = false;
                processed.set(1);
                total.set(1);
                if (resumeEnabled)
                    clearProgressFile();
                logToFile("FINISH", "single region; blocks=" + totalBlocksReplaced.get() + ", entities="
                        + totalEntitiesRemoved.get());
                logger.info(lang.getMessage("log.replace_complete_one"));
            });
            logger.info(lang.getMessage("task.starting"));
            if (invoker != null)
                invoker.sendMessage(lang.getMessage("start.started"));
            return;
        }

        // 多线程分块模式
        executor = Executors.newFixedThreadPool(Math.max(1, parallel));
        int logEvery = Math.max(1, progressLogEvery);

        for (int i = 0; i < parallel; i++) {
            executor.submit(
                    () -> processTiles(total.get(), activeRegionX, activeRegionY, activeRegionZ, logEvery, fastMode));
        }

        // 监控线程
        Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
            try {
                executor.shutdown();
                executor.awaitTermination(7, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running = false;
                if (processed.get() >= total.get()) {
                    clearProgressFile();
                }
                logToFile("FINISH", String.format("regions=%d, processed=%d, blocks=%d, entities=%d",
                        total.get(), processed.get(), totalBlocksReplaced.get(), totalEntitiesRemoved.get()));
                logger.info(lang.getMessage("log.replace_complete_tasks", "count", String.valueOf(processed.get())));
            }
        });

        logger.info(resumed ? lang.getMessage("task.resume_loaded", "index", String.valueOf(processed.get()), "total",
                String.valueOf(total.get())) : lang.getMessage("task.starting"));
        if (invoker != null) {
            if (resumed && processed.get() > 0) {
                invoker.sendMessage(lang.getMessage("start.started"));
                invoker.sendMessage(lang.getMessage("status.progress",
                        "processed", String.valueOf(processed.get()),
                        "total", String.valueOf(total.get()),
                        "percent", String.format("%.1f", (processed.get() * 100.0 / total.get()))));
            } else {
                invoker.sendMessage(lang.getMessage("start.started"));
                invoker.sendMessage(lang.getMessage("start.total_chunks", "total", String.valueOf(total.get())));
            }
        }
    }

    /**
     * 停止清理任务
     */
    public void stop(CommandSender invoker) {
        if (!running) {
            if (invoker != null)
                invoker.sendMessage(lang.getMessage("stop.not_running"));
            return;
        }

        // 先设置标志，让工作线程检测到并停止
        running = false;

        // 中断所有工作线程
        if (executor != null) {
            executor.shutdownNow();
            // 等待线程池关闭（异步等待，不阻塞命令执行）
            Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
                try {
                    if (executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        logger.info(lang.getMessage("log.all_stopped"));
                    } else {
                        logger.warning(lang.getMessage("log.stop_timeout"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warning(lang.getMessage("log.wait_interrupted"));
                }
            });
        }

        // 保存当前进度
        if (resumeEnabled) {
            saveProgress(processed.get());
            logger.info(lang.getMessage("log.progress_saved_resume"));
        }

        logToFile("STOP", String.format("processed=%d/%d, blocks=%d, entities=%d",
                processed.get(), total.get(), totalBlocksReplaced.get(), totalEntitiesRemoved.get()));
        logger.info(lang.getMessage("log.stopping_wait"));
    }

    /**
     * 发送状态信息
     */
    public void sendStatus(CommandSender sender) {
        if (!running) {
            String msg = lang.getMessage("status.not_running");
            if (sender != null) {
                sender.sendMessage(msg);
            } else {
                logger.info(msg);
            }
            return;
        }

        // 计算进度百分比
        double percent = total.get() > 0 ? (processed.get() * 100.0 / total.get()) : 0;

        if (sender != null) {
            sender.sendMessage(lang.getMessage("status.running"));
            sender.sendMessage(lang.getMessage("status.progress",
                    "processed", String.valueOf(processed.get()),
                    "total", String.valueOf(total.get()),
                    "percent", String.format("%.1f", percent)));
            sender.sendMessage(lang.getMessage("status.blocks_replaced",
                    "blocks", String.valueOf(totalBlocksReplaced.get())));
            sender.sendMessage(lang.getMessage("status.entities_removed",
                    "entities", String.valueOf(totalEntitiesRemoved.get())));
        } else {
            logger.info(String.format("Progress: %d/%d (%.1f%%) | Blocks: %d | Entities: %d",
                    processed.get(), total.get(), percent,
                    totalBlocksReplaced.get(), totalEntitiesRemoved.get()));
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 检查内存并等待（如果内存不足）
     * 
     * @return true 如果可以继续，false 如果应该中止
     */
    private boolean waitForMemoryIfNeeded() {
        if (!memoryProtectionEnabled) {
            return true;
        }

        int retries = 0;
        while (retries < maxMemoryRetries || maxMemoryRetries < 0) {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            double freePercent = (double) (maxMemory - usedMemory) / maxMemory;

            if (freePercent >= minFreeMemoryPercent) {
                // 内存充足，可以继续
                if (retries > 0) {
                    logger.info(lang.getMessage("log.memory_recovered", "percent",
                            String.format("%.1f", freePercent * 100)));
                }
                return true;
            }

            // 内存不足，警告并等待
            retries++;
            logger.warning(lang.getMessage("log.memory_warning",
                    "free", String.format("%.1f", freePercent * 100),
                    "min", String.format("%.1f", minFreeMemoryPercent * 100),
                    "wait", String.valueOf(waitOnLowMemoryMs / 1000),
                    "retry", String.valueOf(retries),
                    "max", maxMemoryRetries < 0 ? "∞" : String.valueOf(maxMemoryRetries)));

            // 建议垃圾回收
            System.gc();

            try {
                Thread.sleep(waitOnLowMemoryMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning(lang.getMessage("log.memory_wait_interrupted"));
                return false;
            }

            // 检查任务是否被停止
            if (!running) {
                return false;
            }
        }

        // 达到最大重试次数
        logger.severe(lang.getMessage("log.memory_abort_max_retries", "count", String.valueOf(maxMemoryRetries)));
        logger.severe(lang.getMessage("log.memory_suggestion"));
        running = false;
        return false;
    }

    private void processTiles(int totalTilesInt, int rX, int rY, int rZ, int logEvery, boolean fastMode) {
        for (;;) {
            // 检查任务是否已被停止或线程被中断
            if (!running || Thread.currentThread().isInterrupted()) {
                logger.info(lang.getMessage("log.worker_stopping"));
                return;
            }

            // 内存保护检查
            if (!waitForMemoryIfNeeded()) {
                logger.severe(lang.getMessage("log.worker_memory_abort"));
                return;
            }

            long index = nextTileIndex.getAndIncrement();
            if (index >= totalTileCount)
                return;

            // 批次间延迟
            if (delayBetweenBatchesMs > 0 && index > 0) {
                try {
                    Thread.sleep(delayBetweenBatchesMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            BlockVector3 startLoc = tileIndexToStart(index, rX, rY, rZ);
            int ex = Math.min(startLoc.getBlockX() + rX - 1, taskEndX);
            int ey = Math.min(startLoc.getBlockY() + rY - 1, taskEndY);
            int ez = Math.min(startLoc.getBlockZ() + rZ - 1, taskEndZ);
            BlockVector3 endLoc = BlockVector3.at(ex, ey, ez);

            // 区块间延迟
            if (delayBetweenChunksMs > 0) {
                try {
                    Thread.sleep(delayBetweenChunksMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            long changed = processRegion(startLoc, endLoc, fastMode);
            if (changed >= 0)
                totalBlocksReplaced.addAndGet(changed);

            int done = processed.incrementAndGet();

            // 定期垃圾回收
            if (gcEveryChunks > 0 && done % gcEveryChunks == 0) {
                System.gc();
                logger.fine(lang.getMessage("log.gc_executed", "count", String.valueOf(done)));
            }

            if (done % logEvery == 0 || done == totalTilesInt) {
                logger.info(lang.getMessage("log.progress_log", "done", String.valueOf(done), "total",
                        String.valueOf(totalTilesInt)));
            }
            if (resumeEnabled && (done % resumeSaveEvery == 0 || done == totalTilesInt)) {
                saveProgress(done);
            }
        }
    }

    private BlockVector3 tileIndexToStart(long index, int rX, int rY, int rZ) {
        if (tilesY <= 0 || tilesZ <= 0) {
            return BlockVector3.at(taskStartX, taskStartY, taskStartZ);
        }
        long tilesPerLayer = tilesY * tilesZ;
        long xIndex = index / tilesPerLayer;
        long remainder = index % tilesPerLayer;
        long yIndex = remainder / tilesZ;
        long zIndex = remainder % tilesZ;

        int sx = taskStartX + (int) (xIndex * (long) rX);
        int sy = taskStartY + (int) (yIndex * (long) rY);
        int sz = taskStartZ + (int) (zIndex * (long) rZ);
        return BlockVector3.at(sx, sy, sz);
    }

    private long processRegion(BlockVector3 startLoc, BlockVector3 endLoc, boolean fastMode) {
        // 如果启用了跳过未生成区块的功能，先检查区块是否已生成
        if (skipUngeneratedChunks && !isRegionGenerated(startLoc, endLoc)) {
            skippedChunks.incrementAndGet();
            return 0L;
        }

        boolean hasBlockRules = groupedBlockRules != null && !groupedBlockRules.isEmpty();
        boolean shouldRemoveEntities = entityCleanupEnabled && entityTypes != null && !entityTypes.isEmpty();

        // 纯实体清理不需要创建 FAWE EditSession，也不需要扫描方块实体/刷新高度图。
        if (!hasBlockRules) {
            if (shouldRemoveEntities) {
                long removed = removeEntitiesSync(startLoc, endLoc);
                if (removed > 0)
                    totalEntitiesRemoved.addAndGet(removed);
            }
            return 0L;
        }

        try (EditSession editSession = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(world)
                .build()) {

            Region region = new CuboidRegion(startLoc, endLoc);
            Extent extent = editSession;
            long changedTotal = 0L;
            try {
                for (Map.Entry<com.sk89q.worldedit.world.block.BlockState, BlockType[]> entry : groupedBlockRules
                        .entrySet()) {
                    Mask mask = new BlockTypeMask(extent, entry.getValue());
                    @SuppressWarnings("deprecation")
                    Pattern pattern = new BlockPattern(entry.getKey());
                    editSession.replaceBlocks(region, mask, pattern);
                }
                try {
                    changedTotal = (long) editSession.getBlockChangeCount();
                } catch (Throwable ignored) {
                }
            } catch (MaxChangedBlocksException e) {
                logger.warning(lang.getMessage("log.max_block_limit", "error", e.getMessage()));
            }

            if (changedTotal > 0) {
                // 同步修复：清除与当前方块不匹配的方块实体（包括被替换为空气或被替换为其他非对应类型）
                try {
                    fixMismatchedTileEntitiesSync(startLoc, endLoc);
                } catch (Throwable t) {
                    logger.log(Level.WARNING, lang.getMessage("log.error_cleaning_te"), t);
                }
            }

            if (shouldRemoveEntities) {
                long removed = removeEntitiesSync(startLoc, endLoc);
                if (removed > 0)
                    totalEntitiesRemoved.addAndGet(removed);
            }

            // 修复区块高度图数据（防止出现黑色区块）
            if (autoFixHeightmap && changedTotal > 0) {
                try {
                    refreshChunkHeightmaps(startLoc, endLoc);
                } catch (Throwable t) {
                    logger.log(Level.WARNING, lang.getMessage("log.error_refreshing_heightmap"), t);
                }
            }

            return changedTotal;
        }
    }

    /**
     * 在主线程上清除目标区域内“空气方块上的残留方块实体（TileEntity）”。
     * 这是导致区块保存报错的主要来源：Block=air 但仍有 TE NBT。
     * 仅处理 block.isAir() 的位置，避免误删仍在使用的方块实体。
     */
    private void fixMismatchedTileEntitiesSync(BlockVector3 startLoc, BlockVector3 endLoc) {
        org.bukkit.World bw = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world);
        if (bw == null)
            return;

        final int minX = Math.min(startLoc.getBlockX(), endLoc.getBlockX());
        final int minY = Math.min(startLoc.getBlockY(), endLoc.getBlockY());
        final int minZ = Math.min(startLoc.getBlockZ(), endLoc.getBlockZ());
        final int maxX = Math.max(startLoc.getBlockX(), endLoc.getBlockX());
        final int maxY = Math.max(startLoc.getBlockY(), endLoc.getBlockY());
        final int maxZ = Math.max(startLoc.getBlockZ(), endLoc.getBlockZ());

        final int startChunkX = minX >> 4;
        final int endChunkX = maxX >> 4;
        final int startChunkZ = minZ >> 4;
        final int endChunkZ = maxZ >> 4;

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            long fixed = 0L;
            try {
                for (int cx = startChunkX; cx <= endChunkX; cx++) {
                    for (int cz = startChunkZ; cz <= endChunkZ; cz++) {
                        if (!bw.isChunkLoaded(cx, cz)) {
                            // 避免加载新块：仅修复已加载/即将保存的区域
                            continue;
                        }
                        Chunk chunk = bw.getChunkAt(cx, cz);
                        BlockState[] tiles;
                        try {
                            tiles = chunk.getTileEntities();
                        } catch (Throwable ignored) {
                            // 部分版本 API 可能变动，直接跳过
                            continue;
                        }
                        for (BlockState state : tiles) {
                            int bx = state.getX();
                            int by = state.getY();
                            int bz = state.getZ();
                            if (bx < minX || bx > maxX || by < minY || by > maxY || bz < minZ || bz > maxZ) {
                                continue;
                            }
                            Block block = state.getBlock();
                            Material type = block.getType();
                            boolean compatible = isCompatibleTileState(state, type);
                            if (!compatible) {
                                // 通过 AIR->原始 的方式强制清理不匹配的方块实体，避免错误 NBT 残留
                                BlockData original = block.getBlockData();
                                block.setType(Material.AIR, false);
                                block.setBlockData(original, false);
                                fixed++;
                            }
                        }
                    }
                }
            } finally {
                if (fixed > 0) {
                    logger.info(lang.getMessage("log.fixed_tile_entities", "count", String.valueOf(fixed)));
                    logToFile("FIX_TE", "fixed-mismatched-te=" + fixed
                            + String.format(" in region (%d,%d,%d)->(%d,%d,%d)", minX, minY, minZ, maxX, maxY, maxZ));
                }
                latch.countDown();
            }
        });
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断一个 BlockState（代表某种方块实体）是否与当前位置的实际方块类型兼容。
     * 为尽量安全：未知类型一律视为兼容（返回 true），只对我们确定的常见类型做严格匹配。
     */
    private boolean isCompatibleTileState(BlockState state, Material type) {
        if (type.isAir())
            return false;

        final String name = type.name();

        // 常见容器与机器
        if (state instanceof Chest)
            return type == Material.CHEST || type == Material.TRAPPED_CHEST;
        if (state instanceof Hopper)
            return type == Material.HOPPER;
        if (state instanceof Barrel)
            return type == Material.BARREL;
        if (state instanceof Dispenser)
            return type == Material.DISPENSER;
        if (state instanceof Dropper)
            return type == Material.DROPPER;
        if (state instanceof Furnace)
            return type == Material.FURNACE || type == Material.BLAST_FURNACE || type == Material.SMOKER;
        if (state instanceof BrewingStand)
            return type == Material.BREWING_STAND;

        // 末影箱、潜影盒、讲台、唱片机、信标、刷怪笼、附魔台
        if (state instanceof ShulkerBox)
            return name.endsWith("_SHULKER_BOX") || type == Material.SHULKER_BOX;
        if (state instanceof Jukebox)
            return type == Material.JUKEBOX;
        if (state instanceof Lectern)
            return type == Material.LECTERN;
        if (state instanceof Beacon)
            return type == Material.BEACON;
        if (state instanceof CreatureSpawner)
            return type == Material.SPAWNER;
        if (state instanceof EnchantingTable)
            return type == Material.ENCHANTING_TABLE;

        // 标牌、头颅、旗帜
        if (state instanceof Sign)
            return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN");
        if (state instanceof Skull)
            return name.endsWith("_HEAD") || name.endsWith("_WALL_HEAD");
        if (state instanceof Banner)
            return name.endsWith("_BANNER") || name.endsWith("_WALL_BANNER");

        // 蜂巢/蜂窝、命令方块、潮涌核心、营火
        if (state instanceof Beehive)
            return type == Material.BEEHIVE || type == Material.BEE_NEST;
        if (state instanceof CommandBlock)
            return type == Material.COMMAND_BLOCK || type == Material.CHAIN_COMMAND_BLOCK
                    || type == Material.REPEATING_COMMAND_BLOCK;
        if (state instanceof Conduit)
            return type == Material.CONDUIT;
        if (state instanceof Campfire)
            return type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE;

        // 对未知类型不做强约束，避免误删
        return true;
    }

    private long removeEntitiesSync(BlockVector3 startLoc, BlockVector3 endLoc) {
        org.bukkit.World bw = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world);
        if (bw == null)
            return 0L;

        int minX = Math.min(startLoc.getBlockX(), endLoc.getBlockX());
        int minY = Math.min(startLoc.getBlockY(), endLoc.getBlockY());
        int minZ = Math.min(startLoc.getBlockZ(), endLoc.getBlockZ());
        int maxX = Math.max(startLoc.getBlockX(), endLoc.getBlockX());
        int maxY = Math.max(startLoc.getBlockY(), endLoc.getBlockY());
        int maxZ = Math.max(startLoc.getBlockZ(), endLoc.getBlockZ());

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final long[] removed = { 0L };
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            try {
                org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(
                        new org.bukkit.Location(bw, minX, minY, minZ),
                        new org.bukkit.Location(bw, maxX + 1, maxY + 1, maxZ + 1));
                for (org.bukkit.entity.Entity e : bw.getNearbyEntities(box)) {
                    if (entityTypes.contains(e.getType())) {
                        e.remove();
                        removed[0]++;
                    }
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return removed[0];
    }

    /**
     * 刷新区块高度图数据
     * 防止方块替换后出现黑色区块（高度图未更新）
     */
    private void refreshChunkHeightmaps(BlockVector3 startLoc, BlockVector3 endLoc) {
        org.bukkit.World bw = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world);
        if (bw == null)
            return;

        final int startChunkX = startLoc.getBlockX() >> 4;
        final int startChunkZ = startLoc.getBlockZ() >> 4;
        final int endChunkX = endLoc.getBlockX() >> 4;
        final int endChunkZ = endLoc.getBlockZ() >> 4;

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            try {
                for (int cx = startChunkX; cx <= endChunkX; cx++) {
                    for (int cz = startChunkZ; cz <= endChunkZ; cz++) {
                        if (bw.isChunkLoaded(cx, cz)) {
                            Chunk chunk = bw.getChunkAt(cx, cz);
                            // 强制标记区块需要保存，触发高度图重新计算
                            // 这个技巧会让 Minecraft 在保存时重新生成高度图
                            chunk.setForceLoaded(true);
                            chunk.setForceLoaded(false);
                        }
                    }
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean loadProgressIfAvailable() {
        if (!resumeEnabled || resumeFile == null || !resumeFile.isFile()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(resumeFile);
        String storedWorld = yaml.getString("world");
        if (storedWorld == null || !storedWorld.equalsIgnoreCase(world.getName())) {
            logger.warning(lang.getMessage("log.resume_world_mismatch", "file", resumeFile.getName()));
            return false;
        }

        int storedProcessed = yaml.getInt("processed-tiles", 0);
        int resumeProcessed = Math.max(0, Math.min(storedProcessed, (int) totalTileCount));
        nextTileIndex.set(resumeProcessed);
        processed.set(resumeProcessed);
        totalBlocksReplaced.set(yaml.getLong("blocks-replaced", 0));
        totalEntitiesRemoved.set(yaml.getLong("entities-removed", 0));
        logger.info(lang.getMessage("log.resume_continued", "current", String.valueOf(resumeProcessed), "total",
                String.valueOf(totalTileCount)));
        return true;
    }

    private void saveProgress(int processedTiles) {
        if (!resumeEnabled || resumeFile == null)
            return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world", world.getName());
        yaml.set("processed-tiles", processedTiles);
        yaml.set("blocks-replaced", totalBlocksReplaced.get());
        yaml.set("entities-removed", totalEntitiesRemoved.get());
        yaml.set("timestamp", System.currentTimeMillis());

        File dir = resumeFile.getParentFile();
        if (dir != null && !dir.exists())
            dir.mkdirs();
        synchronized (progressLock) {
            try {
                yaml.save(resumeFile);
            } catch (IOException e) {
                logger.log(Level.WARNING, "写入进度文件失败", e);
            }
        }
    }

    private void clearProgressFile() {
        if (!resumeEnabled || resumeFile == null)
            return;
        synchronized (progressLock) {
            if (resumeFile.isFile() && !resumeFile.delete()) {
                logger.warning(lang.getMessage("log.resume_delete_failed", "file", resumeFile.getName()));
            }
        }
    }

    private void logToFile(String tag, String detail) {
        File dir = dataFolder;
        if (!dir.exists() && !dir.mkdirs())
            return;
        File file = new File(dir, "clean-log.txt");
        String line = String.format("[%s] %s | %s%n", java.time.LocalDateTime.now(), tag, detail);
        synchronized (logLock) {
            try (FileWriter fw = new FileWriter(file, true)) {
                fw.write(line);
            } catch (IOException e) {
                logger.log(Level.WARNING, "写入清理日志失败", e);
            }
        }
    }

    /**
     * 检查区域内的区块是否已生成
     * 只检查关键位置的区块（四个角和中心点）来快速判断
     * 性能优化：缓存 Bukkit 世界对象，避免重复适配器调用
     */
    private org.bukkit.World cachedBukkitWorld = null;

    private boolean isRegionGenerated(BlockVector3 startLoc, BlockVector3 endLoc) {
        // 缓存 Bukkit 世界对象以提升性能
        if (cachedBukkitWorld == null) {
            cachedBukkitWorld = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world);
            if (cachedBukkitWorld == null) {
                logger.warning("无法获取 Bukkit 世界对象，将处理所有区域（不检查区块生成状态）");
                return true; // 如果无法获取 Bukkit 世界，假设已生成以避免跳过
            }
        }

        // 计算区块坐标（区块坐标 = 方块坐标 / 16）
        int startChunkX = startLoc.getBlockX() >> 4;
        int startChunkZ = startLoc.getBlockZ() >> 4;
        int endChunkX = endLoc.getBlockX() >> 4;
        int endChunkZ = endLoc.getBlockZ() >> 4;

        // 检查几个关键区块是否已生成（采样检查）
        // 如果任意采样点已生成，则认为该区域需要处理
        // 这样可以确保清理所有加载过的区块

        // 检查四个角落和中心的区块
        int[][] checkPoints = {
                { startChunkX, startChunkZ }, // 左上角
                { endChunkX, startChunkZ }, // 右上角
                { startChunkX, endChunkZ }, // 左下角
                { endChunkX, endChunkZ }, // 右下角
                { (startChunkX + endChunkX) / 2, (startChunkZ + endChunkZ) / 2 } // 中心
        };

        // 提前返回优化：找到第一个已生成的区块就立即返回
        for (int[] point : checkPoints) {
            if (cachedBukkitWorld.isChunkGenerated(point[0], point[1])) {
                return true;
            }
        }

        return false;
    }

    private static long ceilDiv(long value, int divisor) {
        if (divisor <= 0)
            throw new IllegalArgumentException("divisor must be > 0");
        if (value <= 0L)
            return 0L;
        return (value + divisor - 1L) / divisor;
    }

    private org.bukkit.plugin.Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("FAWEReplace");
    }
}
