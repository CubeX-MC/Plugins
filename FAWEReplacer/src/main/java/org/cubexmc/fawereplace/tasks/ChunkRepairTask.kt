package org.cubexmc.fawereplace.tasks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import org.cubexmc.fawereplace.LanguageManager

/**
 * 区块修复任务
 * 用于修复清理后出现的黑色区块（高度图损坏）
 */
class ChunkRepairTask(
    private val logger: Logger,
    private val world: World,
    private val lang: LanguageManager,
) {
    /**
     * 修复指定范围内的区块
     *
     * @param startX 起始区块 X 坐标
     * @param startZ 起始区块 Z 坐标
     * @param endX   结束区块 X 坐标
     * @param endZ   结束区块 Z 坐标
     * @param sender 命令发送者
     */
    fun repairChunks(startX: Int, startZ: Int, endX: Int, endZ: Int, sender: CommandSender?) {
        val minX = minOf(startX, endX)
        val maxX = maxOf(startX, endX)
        val minZ = minOf(startZ, endZ)
        val maxZ = maxOf(startZ, endZ)

        val totalChunks = (maxX - minX + 1) * (maxZ - minZ + 1)

        if (sender != null) {
            sender.sendMessage(lang.getMessage("repair.starting_msg", "count", totalChunks.toString()))
            sender.sendMessage(
                lang.getMessage(
                    "repair.range_msg",
                    "x1",
                    minX.toString(),
                    "z1",
                    minZ.toString(),
                    "x2",
                    maxX.toString(),
                    "z2",
                    maxZ.toString(),
                ),
            )
        }

        logger.info(lang.getMessage("repair.starting_log", "count", totalChunks.toString()))

        val latch = CountDownLatch(1)
        val repaired = intArrayOf(0)

        Bukkit.getScheduler().runTask(getPlugin(), Runnable {
            try {
                for (x in minX..maxX) {
                    for (z in minZ..maxZ) {
                        if (world.isChunkGenerated(x, z)) {
                            // 加载区块
                            val chunk = world.getChunkAt(x, z)

                            // 方法 1: 标记区块需要保存（强制重新计算高度图）
                            chunk.isForceLoaded = true
                            chunk.isForceLoaded = false

                            // 方法 2: 卸载并重新加载区块（强制保存）
                            world.unloadChunk(chunk)
                            world.loadChunk(x, z)

                            repaired[0]++

                            if (repaired[0] % 100 == 0) {
                                logger.info(
                                    lang.getMessage(
                                        "repair.progress_log",
                                        "done",
                                        repaired[0].toString(),
                                        "total",
                                        totalChunks.toString(),
                                    ),
                                )
                            }
                        }
                    }
                }

                logger.info(lang.getMessage("repair.complete_log", "count", repaired[0].toString()))
                if (sender != null) {
                    sender.sendMessage(lang.getMessage("repair.complete_msg", "count", repaired[0].toString()))
                    sender.sendMessage(lang.getMessage("repair.restart_msg"))
                }
            } catch (exception: Exception) {
                logger.severe(lang.getMessage("repair.error_log", "error", exception.message))
                exception.printStackTrace()
            } finally {
                latch.countDown()
            }
        })

        try {
            latch.await(30, TimeUnit.MINUTES)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun getPlugin(): Plugin =
        Bukkit.getPluginManager().getPlugin("FAWEReplace") ?: error("FAWEReplace plugin not found")
}
