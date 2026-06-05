@file:Suppress("DEPRECATION")

package org.cubexmc.fawereplace.tasks

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.MaxChangedBlocksException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.function.mask.BlockTypeMask
import com.sk89q.worldedit.function.mask.Mask
import com.sk89q.worldedit.function.pattern.BlockPattern
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.block.BlockType
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Banner
import org.bukkit.block.Barrel
import org.bukkit.block.Beacon
import org.bukkit.block.Beehive
import org.bukkit.block.BlockState
import org.bukkit.block.BrewingStand
import org.bukkit.block.Campfire
import org.bukkit.block.Chest
import org.bukkit.block.CommandBlock
import org.bukkit.block.Conduit
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.Dispenser
import org.bukkit.block.Dropper
import org.bukkit.block.EnchantingTable
import org.bukkit.block.Furnace
import org.bukkit.block.Hopper
import org.bukkit.block.Jukebox
import org.bukkit.block.Lectern
import org.bukkit.block.ShulkerBox
import org.bukkit.block.Sign
import org.bukkit.block.Skull
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.cubexmc.fawereplace.LanguageManager

/**
 * 清理任务的核心逻辑封装
 */
class CleaningTask(
    private val logger: Logger,
    private val dataFolder: File,
    private val lang: LanguageManager,
) {
    // 任务状态
    @Volatile
    var isRunning: Boolean = false
        private set
    private var executor: ExecutorService? = null

    // 统计数据
    private val processed = AtomicInteger(0)
    private val total = AtomicInteger(0)
    private val totalBlocksReplaced = AtomicLong(0)
    private val totalEntitiesRemoved = AtomicLong(0)

    // 任务配置
    private var world: World? = null
    private var taskStartX = 0
    private var taskStartY = 0
    private var taskStartZ = 0
    private var taskEndX = 0
    private var taskEndY = 0
    private var taskEndZ = 0
    private var parallel = 0
    private var tiling = false
    private var fastMode = false
    private var resumeEnabled = false
    private var resumeSaveEvery = 0
    private var resumeFile: File? = null

    // 分块配置
    private val nextTileIndex = AtomicLong(0)
    private var tilesX = 0L
    private var tilesY = 0L
    private var tilesZ = 0L
    private var totalTileCount = 0L
    private var activeRegionX = 0
    private var activeRegionY = 0
    private var activeRegionZ = 0

    // 方块规则
    private var groupedBlockRules: Map<com.sk89q.worldedit.world.block.BlockState, Array<BlockType>>? = null

    // 实体清理
    private var entityCleanupEnabled = false
    private var entityTypes: Set<org.bukkit.entity.EntityType>? = null

    // 区块检查
    private var skipUngeneratedChunks = false
    private val skippedChunks = AtomicLong(0)

    // 内存保护
    private var memoryProtectionEnabled = false
    private var minFreeMemoryPercent = 0.0
    private var waitOnLowMemoryMs = 0L
    private var maxMemoryRetries = 0

    // 性能限制
    private var delayBetweenBatchesMs = 0L
    private var delayBetweenChunksMs = 0L
    private var gcEveryChunks = 0
    private var progressLogEvery = 100
    private var autoFixHeightmap = true

    // 日志锁
    private val logLock = Any()
    private val progressLock = Any()

    // 缓存 Bukkit 世界对象以提升性能
    private var cachedBukkitWorld: org.bukkit.World? = null

    /**
     * 配置任务参数
     */
    fun configure(
        world: World,
        startX: Int,
        startY: Int,
        startZ: Int,
        endX: Int,
        endY: Int,
        endZ: Int,
        parallel: Int,
        tiling: Boolean,
        fastMode: Boolean,
        blockRules: Map<com.sk89q.worldedit.world.block.BlockState, Array<BlockType>>,
        entityCleanup: Boolean,
        entities: Set<org.bukkit.entity.EntityType>,
        resumeEnabled: Boolean,
        resumeSaveEvery: Int,
        resumeFile: File,
        skipUngeneratedChunks: Boolean,
        memoryProtection: Boolean,
        minFreeMemory: Double,
        waitOnLowMemory: Long,
        maxRetries: Int,
        delayBetweenBatches: Long,
        delayBetweenChunks: Long,
        gcEvery: Int,
        progressLogEvery: Int,
        autoFixHeightmap: Boolean,
    ) {
        this.world = world
        taskStartX = minOf(startX, endX)
        taskStartY = minOf(startY, endY)
        taskStartZ = minOf(startZ, endZ)
        taskEndX = maxOf(startX, endX)
        taskEndY = maxOf(startY, endY)
        taskEndZ = maxOf(startZ, endZ)
        this.parallel = parallel
        this.tiling = tiling
        this.fastMode = fastMode
        groupedBlockRules = blockRules
        entityCleanupEnabled = entityCleanup
        entityTypes = entities
        this.resumeEnabled = resumeEnabled
        this.resumeSaveEvery = resumeSaveEvery
        this.resumeFile = resumeFile
        this.skipUngeneratedChunks = skipUngeneratedChunks
        skippedChunks.set(0)
        cachedBukkitWorld = null

        // 内存保护和性能配置
        memoryProtectionEnabled = memoryProtection
        minFreeMemoryPercent = minFreeMemory
        waitOnLowMemoryMs = waitOnLowMemory
        maxMemoryRetries = maxRetries
        delayBetweenBatchesMs = delayBetweenBatches
        delayBetweenChunksMs = delayBetweenChunks
        gcEveryChunks = gcEvery
        this.progressLogEvery = maxOf(1, progressLogEvery)
        this.autoFixHeightmap = autoFixHeightmap
    }

    /**
     * 设置分块大小
     */
    fun setRegionSize(regionX: Int, regionY: Int, regionZ: Int) {
        activeRegionX = regionX
        activeRegionY = regionY
        activeRegionZ = regionZ

        // 计算分块数量
        tilesX = maxOf(1L, ceilDiv(taskEndX.toLong() - taskStartX.toLong() + 1L, regionX))
        tilesY = maxOf(1L, ceilDiv(taskEndY.toLong() - taskStartY.toLong() + 1L, regionY))
        tilesZ = maxOf(1L, ceilDiv(taskEndZ.toLong() - taskStartZ.toLong() + 1L, regionZ))
        totalTileCount = tilesX * tilesY * tilesZ
    }

    /**
     * 开始清理任务
     *
     * @param invoker 命令发送者
     */
    fun start(invoker: CommandSender?) {
        start(invoker, false)
    }

    /**
     * 开始清理任务
     *
     * @param invoker      命令发送者
     * @param forceRestart 是否强制重新开始（忽略之前的进度）
     */
    fun start(invoker: CommandSender?, forceRestart: Boolean) {
        if (isRunning) {
            invoker?.sendMessage(lang.getMessage("start.already_running"))
            return
        }

        processed.set(0)
        totalBlocksReplaced.set(0)
        totalEntitiesRemoved.set(0)
        nextTileIndex.set(0L)

        // 尝试加载进度（除非强制重新开始）
        var resumed = false
        if (resumeEnabled && tiling && !forceRestart) {
            resumed = loadProgressIfAvailable()
            if (resumed && invoker != null) {
                invoker.sendMessage(
                    lang.getMessage("start.resume_found", "progress", processed.get().toString() + "/" + total.get()),
                )
                invoker.sendMessage(lang.getMessage("start.resume_use_fresh"))
            }
        } else if (forceRestart) {
            // 如果强制重新开始，删除进度文件
            clearProgressFile()
            invoker?.sendMessage(lang.getMessage("start.starting_fresh"))
        }

        total.set(minOf(totalTileCount, Int.MAX_VALUE.toLong()).toInt())

        logToFile(
            "START",
            String.format(
                "world=%s, region=%dx%dx%d, area=(%d,%d,%d)->(%d,%d,%d), parallel=%d, tiling=%s, resume=%s",
                requireWorld().name,
                activeRegionX,
                activeRegionY,
                activeRegionZ,
                taskStartX,
                taskStartY,
                taskStartZ,
                taskEndX,
                taskEndY,
                taskEndZ,
                parallel,
                tiling,
                if (resumed) "resumed" else "fresh",
            ),
        )

        isRunning = true

        if (!tiling) {
            // 单区域模式
            Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), Runnable {
                val changed = processRegion(
                    BlockVector3.at(taskStartX, taskStartY, taskStartZ),
                    BlockVector3.at(taskEndX, taskEndY, taskEndZ),
                    fastMode,
                )
                if (changed >= 0) {
                    totalBlocksReplaced.addAndGet(changed)
                }
                isRunning = false
                processed.set(1)
                total.set(1)
                if (resumeEnabled) {
                    clearProgressFile()
                }
                logToFile(
                    "FINISH",
                    "single region; blocks=" + totalBlocksReplaced.get() + ", entities=" + totalEntitiesRemoved.get(),
                )
                logger.info(lang.getMessage("log.replace_complete_one"))
            })
            logger.info(lang.getMessage("task.starting"))
            invoker?.sendMessage(lang.getMessage("start.started"))
            return
        }

        // 多线程分块模式
        executor = Executors.newFixedThreadPool(maxOf(1, parallel))
        val logEvery = maxOf(1, progressLogEvery)
        val currentExecutor = executor ?: error("Executor not initialized")

        for (i in 0 until parallel) {
            currentExecutor.submit(Runnable {
                processTiles(total.get(), activeRegionX, activeRegionY, activeRegionZ, logEvery, fastMode)
            })
        }

        // 监控线程
        Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), Runnable {
            try {
                currentExecutor.shutdown()
                currentExecutor.awaitTermination(7, TimeUnit.DAYS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                isRunning = false
                if (processed.get() >= total.get()) {
                    clearProgressFile()
                }
                logToFile(
                    "FINISH",
                    String.format(
                        "regions=%d, processed=%d, blocks=%d, entities=%d",
                        total.get(),
                        processed.get(),
                        totalBlocksReplaced.get(),
                        totalEntitiesRemoved.get(),
                    ),
                )
                logger.info(lang.getMessage("log.replace_complete_tasks", "count", processed.get().toString()))
            }
        })

        logger.info(
            if (resumed) {
                lang.getMessage("task.resume_loaded", "index", processed.get().toString(), "total", total.get().toString())
            } else {
                lang.getMessage("task.starting")
            },
        )
        if (invoker != null) {
            if (resumed && processed.get() > 0) {
                invoker.sendMessage(lang.getMessage("start.started"))
                invoker.sendMessage(
                    lang.getMessage(
                        "status.progress",
                        "processed",
                        processed.get().toString(),
                        "total",
                        total.get().toString(),
                        "percent",
                        String.format("%.1f", processed.get() * 100.0 / total.get()),
                    ),
                )
            } else {
                invoker.sendMessage(lang.getMessage("start.started"))
                invoker.sendMessage(lang.getMessage("start.total_chunks", "total", total.get().toString()))
            }
        }
    }

    /**
     * 停止清理任务
     */
    fun stop(invoker: CommandSender?) {
        if (!isRunning) {
            invoker?.sendMessage(lang.getMessage("stop.not_running"))
            return
        }

        // 先设置标志，让工作线程检测到并停止
        isRunning = false

        // 中断所有工作线程
        val currentExecutor = executor
        if (currentExecutor != null) {
            currentExecutor.shutdownNow()
            // 等待线程池关闭（异步等待，不阻塞命令执行）
            Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), Runnable {
                try {
                    if (currentExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                        logger.info(lang.getMessage("log.all_stopped"))
                    } else {
                        logger.warning(lang.getMessage("log.stop_timeout"))
                    }
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warning(lang.getMessage("log.wait_interrupted"))
                }
            })
        }

        // 保存当前进度
        if (resumeEnabled) {
            saveProgress(processed.get())
            logger.info(lang.getMessage("log.progress_saved_resume"))
        }

        logToFile(
            "STOP",
            String.format(
                "processed=%d/%d, blocks=%d, entities=%d",
                processed.get(),
                total.get(),
                totalBlocksReplaced.get(),
                totalEntitiesRemoved.get(),
            ),
        )
        logger.info(lang.getMessage("log.stopping_wait"))
    }

    /**
     * 发送状态信息
     */
    fun sendStatus(sender: CommandSender?) {
        if (!isRunning) {
            val message = lang.getMessage("status.not_running")
            if (sender != null) {
                sender.sendMessage(message)
            } else {
                logger.info(message)
            }
            return
        }

        // 计算进度百分比
        val percent = if (total.get() > 0) processed.get() * 100.0 / total.get() else 0.0

        if (sender != null) {
            sender.sendMessage(lang.getMessage("status.running"))
            sender.sendMessage(
                lang.getMessage(
                    "status.progress",
                    "processed",
                    processed.get().toString(),
                    "total",
                    total.get().toString(),
                    "percent",
                    String.format("%.1f", percent),
                ),
            )
            sender.sendMessage(lang.getMessage("status.blocks_replaced", "blocks", totalBlocksReplaced.get().toString()))
            sender.sendMessage(lang.getMessage("status.entities_removed", "entities", totalEntitiesRemoved.get().toString()))
        } else {
            logger.info(
                String.format(
                    "Progress: %d/%d (%.1f%%) | Blocks: %d | Entities: %d",
                    processed.get(),
                    total.get(),
                    percent,
                    totalBlocksReplaced.get(),
                    totalEntitiesRemoved.get(),
                ),
            )
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 检查内存并等待（如果内存不足）
     *
     * @return true 如果可以继续，false 如果应该中止
     */
    private fun waitForMemoryIfNeeded(): Boolean {
        if (!memoryProtectionEnabled) {
            return true
        }

        var retries = 0
        while (retries < maxMemoryRetries || maxMemoryRetries < 0) {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            val freePercent = (maxMemory - usedMemory).toDouble() / maxMemory

            if (freePercent >= minFreeMemoryPercent) {
                // 内存充足，可以继续
                if (retries > 0) {
                    logger.info(lang.getMessage("log.memory_recovered", "percent", String.format("%.1f", freePercent * 100)))
                }
                return true
            }

            // 内存不足，警告并等待
            retries++
            logger.warning(
                lang.getMessage(
                    "log.memory_warning",
                    "free",
                    String.format("%.1f", freePercent * 100),
                    "min",
                    String.format("%.1f", minFreeMemoryPercent * 100),
                    "wait",
                    (waitOnLowMemoryMs / 1000).toString(),
                    "retry",
                    retries.toString(),
                    "max",
                    if (maxMemoryRetries < 0) "∞" else maxMemoryRetries.toString(),
                ),
            )

            // 建议垃圾回收
            System.gc()

            try {
                Thread.sleep(waitOnLowMemoryMs)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warning(lang.getMessage("log.memory_wait_interrupted"))
                return false
            }

            // 检查任务是否被停止
            if (!isRunning) {
                return false
            }
        }

        // 达到最大重试次数
        logger.severe(lang.getMessage("log.memory_abort_max_retries", "count", maxMemoryRetries.toString()))
        logger.severe(lang.getMessage("log.memory_suggestion"))
        isRunning = false
        return false
    }

    private fun processTiles(totalTilesInt: Int, regionX: Int, regionY: Int, regionZ: Int, logEvery: Int, fastMode: Boolean) {
        while (true) {
            // 检查任务是否已被停止或线程被中断
            if (!isRunning || Thread.currentThread().isInterrupted) {
                logger.info(lang.getMessage("log.worker_stopping"))
                return
            }

            // 内存保护检查
            if (!waitForMemoryIfNeeded()) {
                logger.severe(lang.getMessage("log.worker_memory_abort"))
                return
            }

            val index = nextTileIndex.getAndIncrement()
            if (index >= totalTileCount) {
                return
            }

            // 批次间延迟
            if (delayBetweenBatchesMs > 0 && index > 0) {
                try {
                    Thread.sleep(delayBetweenBatchesMs)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }

            val startLoc = tileIndexToStart(index, regionX, regionY, regionZ)
            val endX = minOf(startLoc.blockX + regionX - 1, taskEndX)
            val endY = minOf(startLoc.blockY + regionY - 1, taskEndY)
            val endZ = minOf(startLoc.blockZ + regionZ - 1, taskEndZ)
            val endLoc = BlockVector3.at(endX, endY, endZ)

            // 区块间延迟
            if (delayBetweenChunksMs > 0) {
                try {
                    Thread.sleep(delayBetweenChunksMs)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }

            val changed = processRegion(startLoc, endLoc, fastMode)
            if (changed >= 0) {
                totalBlocksReplaced.addAndGet(changed)
            }

            val done = processed.incrementAndGet()

            // 定期垃圾回收
            if (gcEveryChunks > 0 && done % gcEveryChunks == 0) {
                System.gc()
                logger.fine(lang.getMessage("log.gc_executed", "count", done.toString()))
            }

            if (done % logEvery == 0 || done == totalTilesInt) {
                logger.info(
                    lang.getMessage("log.progress_log", "done", done.toString(), "total", totalTilesInt.toString()),
                )
            }
            if (resumeEnabled && (done % resumeSaveEvery == 0 || done == totalTilesInt)) {
                saveProgress(done)
            }
        }
    }

    private fun tileIndexToStart(index: Long, regionX: Int, regionY: Int, regionZ: Int): BlockVector3 {
        if (tilesY <= 0 || tilesZ <= 0) {
            return BlockVector3.at(taskStartX, taskStartY, taskStartZ)
        }
        val tilesPerLayer = tilesY * tilesZ
        val xIndex = index / tilesPerLayer
        val remainder = index % tilesPerLayer
        val yIndex = remainder / tilesZ
        val zIndex = remainder % tilesZ

        val startX = taskStartX + (xIndex * regionX.toLong()).toInt()
        val startY = taskStartY + (yIndex * regionY.toLong()).toInt()
        val startZ = taskStartZ + (zIndex * regionZ.toLong()).toInt()
        return BlockVector3.at(startX, startY, startZ)
    }

    private fun processRegion(startLoc: BlockVector3, endLoc: BlockVector3, fastMode: Boolean): Long {
        // 如果启用了跳过未生成区块的功能，先检查区块是否已生成
        if (skipUngeneratedChunks && !isRegionGenerated(startLoc, endLoc)) {
            skippedChunks.incrementAndGet()
            return 0L
        }

        val blockRules = groupedBlockRules
        val currentEntityTypes = entityTypes
        val hasBlockRules = blockRules != null && blockRules.isNotEmpty()
        val shouldRemoveEntities = entityCleanupEnabled && currentEntityTypes != null && currentEntityTypes.isNotEmpty()

        // 纯实体清理不需要创建 FAWE EditSession，也不需要扫描方块实体/刷新高度图。
        if (!hasBlockRules) {
            if (shouldRemoveEntities) {
                val removed = removeEntitiesSync(startLoc, endLoc)
                if (removed > 0) {
                    totalEntitiesRemoved.addAndGet(removed)
                }
            }
            return 0L
        }

        WorldEdit.getInstance()
            .newEditSessionBuilder()
            .world(requireWorld())
            .build()
            .use { editSession ->
                val region: Region = CuboidRegion(startLoc, endLoc)
                val extent: Extent = editSession
                var changedTotal = 0L
                try {
                    for ((targetState, origins) in blockRules) {
                        val mask: Mask = BlockTypeMask(extent, *origins)
                        @Suppress("DEPRECATION")
                        val pattern: Pattern = BlockPattern(targetState)
                        editSession.replaceBlocks(region, mask, pattern)
                    }
                    try {
                        changedTotal = editSession.blockChangeCount.toLong()
                    } catch (ignored: Throwable) {
                    }
                } catch (exception: MaxChangedBlocksException) {
                    logger.warning(lang.getMessage("log.max_block_limit", "error", exception.message))
                }

                if (changedTotal > 0) {
                    // 同步修复：清除与当前方块不匹配的方块实体（包括被替换为空气或被替换为其他非对应类型）
                    try {
                        fixMismatchedTileEntitiesSync(startLoc, endLoc)
                    } catch (throwable: Throwable) {
                        logger.log(Level.WARNING, lang.getMessage("log.error_cleaning_te"), throwable)
                    }
                }

                if (shouldRemoveEntities) {
                    val removed = removeEntitiesSync(startLoc, endLoc)
                    if (removed > 0) {
                        totalEntitiesRemoved.addAndGet(removed)
                    }
                }

                // 修复区块高度图数据（防止出现黑色区块）
                if (autoFixHeightmap && changedTotal > 0) {
                    try {
                        refreshChunkHeightmaps(startLoc, endLoc)
                    } catch (throwable: Throwable) {
                        logger.log(Level.WARNING, lang.getMessage("log.error_refreshing_heightmap"), throwable)
                    }
                }

                return changedTotal
            }
    }

    /**
     * 在主线程上清除目标区域内“空气方块上的残留方块实体（TileEntity）”。
     * 这是导致区块保存报错的主要来源：Block=air 但仍有 TE NBT。
     * 仅处理 block.isAir() 的位置，避免误删仍在使用的方块实体。
     */
    private fun fixMismatchedTileEntitiesSync(startLoc: BlockVector3, endLoc: BlockVector3) {
        val bukkitWorld = BukkitAdapter.adapt(requireWorld()) ?: return

        val minX = minOf(startLoc.blockX, endLoc.blockX)
        val minY = minOf(startLoc.blockY, endLoc.blockY)
        val minZ = minOf(startLoc.blockZ, endLoc.blockZ)
        val maxX = maxOf(startLoc.blockX, endLoc.blockX)
        val maxY = maxOf(startLoc.blockY, endLoc.blockY)
        val maxZ = maxOf(startLoc.blockZ, endLoc.blockZ)

        val startChunkX = minX shr 4
        val endChunkX = maxX shr 4
        val startChunkZ = minZ shr 4
        val endChunkZ = maxZ shr 4

        val latch = CountDownLatch(1)
        Bukkit.getScheduler().runTask(getPlugin(), Runnable {
            var fixed = 0L
            try {
                for (chunkX in startChunkX..endChunkX) {
                    for (chunkZ in startChunkZ..endChunkZ) {
                        if (!bukkitWorld.isChunkLoaded(chunkX, chunkZ)) {
                            // 避免加载新块：仅修复已加载/即将保存的区域
                            continue
                        }
                        val chunk: Chunk = bukkitWorld.getChunkAt(chunkX, chunkZ)
                        val tiles: Array<BlockState> = try {
                            chunk.tileEntities
                        } catch (ignored: Throwable) {
                            // 部分版本 API 可能变动，直接跳过
                            continue
                        }
                        for (state in tiles) {
                            val blockX = state.x
                            val blockY = state.y
                            val blockZ = state.z
                            if (blockX < minX || blockX > maxX || blockY < minY || blockY > maxY || blockZ < minZ || blockZ > maxZ) {
                                continue
                            }
                            val block = state.block
                            val type = block.type
                            val compatible = isCompatibleTileState(state, type)
                            if (!compatible) {
                                // 通过 AIR->原始 的方式强制清理不匹配的方块实体，避免错误 NBT 残留
                                val original = block.blockData
                                block.setType(Material.AIR, false)
                                block.setBlockData(original, false)
                                fixed++
                            }
                        }
                    }
                }
            } finally {
                if (fixed > 0) {
                    logger.info(lang.getMessage("log.fixed_tile_entities", "count", fixed.toString()))
                    logToFile(
                        "FIX_TE",
                        "fixed-mismatched-te=$fixed" +
                            String.format(" in region (%d,%d,%d)->(%d,%d,%d)", minX, minY, minZ, maxX, maxY, maxZ),
                    )
                }
                latch.countDown()
            }
        })
        try {
            latch.await(60, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 判断一个 BlockState（代表某种方块实体）是否与当前位置的实际方块类型兼容。
     * 为尽量安全：未知类型一律视为兼容（返回 true），只对我们确定的常见类型做严格匹配。
     */
    private fun isCompatibleTileState(state: BlockState, type: Material): Boolean {
        if (type.isAir) {
            return false
        }

        val name = type.name

        // 常见容器与机器
        if (state is Chest) return type == Material.CHEST || type == Material.TRAPPED_CHEST
        if (state is Hopper) return type == Material.HOPPER
        if (state is Barrel) return type == Material.BARREL
        if (state is Dispenser) return type == Material.DISPENSER
        if (state is Dropper) return type == Material.DROPPER
        if (state is Furnace) return type == Material.FURNACE || type == Material.BLAST_FURNACE || type == Material.SMOKER
        if (state is BrewingStand) return type == Material.BREWING_STAND

        // 末影箱、潜影盒、讲台、唱片机、信标、刷怪笼、附魔台
        if (state is ShulkerBox) return name.endsWith("_SHULKER_BOX") || type == Material.SHULKER_BOX
        if (state is Jukebox) return type == Material.JUKEBOX
        if (state is Lectern) return type == Material.LECTERN
        if (state is Beacon) return type == Material.BEACON
        if (state is CreatureSpawner) return type == Material.SPAWNER
        if (state is EnchantingTable) return type == Material.ENCHANTING_TABLE

        // 标牌、头颅、旗帜
        if (state is Sign) return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN")
        if (state is Skull) return name.endsWith("_HEAD") || name.endsWith("_WALL_HEAD")
        if (state is Banner) return name.endsWith("_BANNER") || name.endsWith("_WALL_BANNER")

        // 蜂巢/蜂窝、命令方块、潮涌核心、营火
        if (state is Beehive) return type == Material.BEEHIVE || type == Material.BEE_NEST
        if (state is CommandBlock) {
            return type == Material.COMMAND_BLOCK ||
                type == Material.CHAIN_COMMAND_BLOCK ||
                type == Material.REPEATING_COMMAND_BLOCK
        }
        if (state is Conduit) return type == Material.CONDUIT
        if (state is Campfire) return type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE

        // 对未知类型不做强约束，避免误删
        return true
    }

    private fun removeEntitiesSync(startLoc: BlockVector3, endLoc: BlockVector3): Long {
        val bukkitWorld = BukkitAdapter.adapt(requireWorld()) ?: return 0L

        val minX = minOf(startLoc.blockX, endLoc.blockX)
        val minY = minOf(startLoc.blockY, endLoc.blockY)
        val minZ = minOf(startLoc.blockZ, endLoc.blockZ)
        val maxX = maxOf(startLoc.blockX, endLoc.blockX)
        val maxY = maxOf(startLoc.blockY, endLoc.blockY)
        val maxZ = maxOf(startLoc.blockZ, endLoc.blockZ)

        val latch = CountDownLatch(1)
        val removed = longArrayOf(0L)
        Bukkit.getScheduler().runTask(getPlugin(), Runnable {
            try {
                val box = BoundingBox.of(
                    Location(bukkitWorld, minX.toDouble(), minY.toDouble(), minZ.toDouble()),
                    Location(bukkitWorld, (maxX + 1).toDouble(), (maxY + 1).toDouble(), (maxZ + 1).toDouble()),
                )
                val currentEntityTypes = entityTypes ?: emptySet()
                for (entity in bukkitWorld.getNearbyEntities(box)) {
                    if (currentEntityTypes.contains(entity.type)) {
                        entity.remove()
                        removed[0]++
                    }
                }
            } finally {
                latch.countDown()
            }
        })
        try {
            latch.await(60, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return removed[0]
    }

    /**
     * 刷新区块高度图数据
     * 防止方块替换后出现黑色区块（高度图未更新）
     */
    private fun refreshChunkHeightmaps(startLoc: BlockVector3, endLoc: BlockVector3) {
        val bukkitWorld = BukkitAdapter.adapt(requireWorld()) ?: return

        val startChunkX = startLoc.blockX shr 4
        val startChunkZ = startLoc.blockZ shr 4
        val endChunkX = endLoc.blockX shr 4
        val endChunkZ = endLoc.blockZ shr 4

        val latch = CountDownLatch(1)
        Bukkit.getScheduler().runTask(getPlugin(), Runnable {
            try {
                for (chunkX in startChunkX..endChunkX) {
                    for (chunkZ in startChunkZ..endChunkZ) {
                        if (bukkitWorld.isChunkLoaded(chunkX, chunkZ)) {
                            val chunk = bukkitWorld.getChunkAt(chunkX, chunkZ)
                            // 强制标记区块需要保存，触发高度图重新计算
                            // 这个技巧会让 Minecraft 在保存时重新生成高度图
                            chunk.isForceLoaded = true
                            chunk.isForceLoaded = false
                        }
                    }
                }
            } finally {
                latch.countDown()
            }
        })
        try {
            latch.await(10, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun loadProgressIfAvailable(): Boolean {
        val currentResumeFile = resumeFile
        if (!resumeEnabled || currentResumeFile == null || !currentResumeFile.isFile) {
            return false
        }
        val yaml = YamlConfiguration.loadConfiguration(currentResumeFile)
        val storedWorld = yaml.getString("world")
        if (storedWorld == null || !storedWorld.equals(requireWorld().name, ignoreCase = true)) {
            logger.warning(lang.getMessage("log.resume_world_mismatch", "file", currentResumeFile.name))
            return false
        }

        val storedProcessed = yaml.getInt("processed-tiles", 0)
        val resumeProcessed = maxOf(0, minOf(storedProcessed, totalTileCount.toInt()))
        nextTileIndex.set(resumeProcessed.toLong())
        processed.set(resumeProcessed)
        totalBlocksReplaced.set(yaml.getLong("blocks-replaced", 0))
        totalEntitiesRemoved.set(yaml.getLong("entities-removed", 0))
        logger.info(
            lang.getMessage("log.resume_continued", "current", resumeProcessed.toString(), "total", totalTileCount.toString()),
        )
        return true
    }

    private fun saveProgress(processedTiles: Int) {
        val currentResumeFile = resumeFile
        if (!resumeEnabled || currentResumeFile == null) {
            return
        }
        val yaml = YamlConfiguration()
        yaml.set("world", requireWorld().name)
        yaml.set("processed-tiles", processedTiles)
        yaml.set("blocks-replaced", totalBlocksReplaced.get())
        yaml.set("entities-removed", totalEntitiesRemoved.get())
        yaml.set("timestamp", System.currentTimeMillis())

        val dir = currentResumeFile.parentFile
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
        synchronized(progressLock) {
            try {
                yaml.save(currentResumeFile)
            } catch (exception: IOException) {
                logger.log(Level.WARNING, "写入进度文件失败", exception)
            }
        }
    }

    private fun clearProgressFile() {
        val currentResumeFile = resumeFile
        if (!resumeEnabled || currentResumeFile == null) {
            return
        }
        synchronized(progressLock) {
            if (currentResumeFile.isFile && !currentResumeFile.delete()) {
                logger.warning(lang.getMessage("log.resume_delete_failed", "file", currentResumeFile.name))
            }
        }
    }

    private fun logToFile(tag: String, detail: String) {
        val dir = dataFolder
        if (!dir.exists() && !dir.mkdirs()) {
            return
        }
        val file = File(dir, "clean-log.txt")
        val line = String.format("[%s] %s | %s%n", LocalDateTime.now(), tag, detail)
        synchronized(logLock) {
            try {
                FileWriter(file, true).use { writer -> writer.write(line) }
            } catch (exception: IOException) {
                logger.log(Level.WARNING, "写入清理日志失败", exception)
            }
        }
    }

    /**
     * 检查区域内的区块是否已生成
     * 只检查关键位置的区块（四个角和中心点）来快速判断
     * 性能优化：缓存 Bukkit 世界对象，避免重复适配器调用
     */
    private fun isRegionGenerated(startLoc: BlockVector3, endLoc: BlockVector3): Boolean {
        // 缓存 Bukkit 世界对象以提升性能
        if (cachedBukkitWorld == null) {
            cachedBukkitWorld = BukkitAdapter.adapt(requireWorld())
            if (cachedBukkitWorld == null) {
                logger.warning("无法获取 Bukkit 世界对象，将处理所有区域（不检查区块生成状态）")
                return true // 如果无法获取 Bukkit 世界，假设已生成以避免跳过
            }
        }

        val bukkitWorld = cachedBukkitWorld ?: return true

        // 计算区块坐标（区块坐标 = 方块坐标 / 16）
        val startChunkX = startLoc.blockX shr 4
        val startChunkZ = startLoc.blockZ shr 4
        val endChunkX = endLoc.blockX shr 4
        val endChunkZ = endLoc.blockZ shr 4

        // 检查几个关键区块是否已生成（采样检查）
        // 如果任意采样点已生成，则认为该区域需要处理
        // 这样可以确保清理所有加载过的区块

        // 检查四个角落和中心的区块
        val checkPoints = arrayOf(
            intArrayOf(startChunkX, startChunkZ), // 左上角
            intArrayOf(endChunkX, startChunkZ), // 右上角
            intArrayOf(startChunkX, endChunkZ), // 左下角
            intArrayOf(endChunkX, endChunkZ), // 右下角
            intArrayOf((startChunkX + endChunkX) / 2, (startChunkZ + endChunkZ) / 2), // 中心
        )

        // 提前返回优化：找到第一个已生成的区块就立即返回
        for (point in checkPoints) {
            if (bukkitWorld.isChunkGenerated(point[0], point[1])) {
                return true
            }
        }

        return false
    }

    private fun requireWorld(): World =
        world ?: error("CleaningTask is not configured with a World")

    private fun getPlugin(): Plugin =
        Bukkit.getPluginManager().getPlugin("FAWEReplace") ?: error("FAWEReplace plugin not found")

    private companion object {
        fun ceilDiv(value: Long, divisor: Int): Long {
            require(divisor > 0) { "divisor must be > 0" }
            if (value <= 0L) {
                return 0L
            }
            return (value + divisor - 1L) / divisor
        }
    }
}
