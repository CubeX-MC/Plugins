package org.cubexmc.fawereplace.tasks;

import org.cubexmc.fawereplace.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 区块修复任务
 * 用于修复清理后出现的黑色区块（高度图损坏）
 */
public class ChunkRepairTask {

    private final Logger logger;
    private final World world;
    private final LanguageManager lang;

    public ChunkRepairTask(Logger logger, World world, LanguageManager lang) {
        this.logger = logger;
        this.world = world;
        this.lang = lang;
    }

    /**
     * 修复指定范围内的区块
     * 
     * @param startX 起始区块 X 坐标
     * @param startZ 起始区块 Z 坐标
     * @param endX   结束区块 X 坐标
     * @param endZ   结束区块 Z 坐标
     * @param sender 命令发送者
     */
    public void repairChunks(int startX, int startZ, int endX, int endZ, CommandSender sender) {
        int minX = Math.min(startX, endX);
        int maxX = Math.max(startX, endX);
        int minZ = Math.min(startZ, endZ);
        int maxZ = Math.max(startZ, endZ);

        int totalChunks = (maxX - minX + 1) * (maxZ - minZ + 1);

        if (sender != null) {
            sender.sendMessage(lang.getMessage("repair.starting_msg", "count", String.valueOf(totalChunks)));
            sender.sendMessage(lang.getMessage("repair.range_msg",
                    "x1", String.valueOf(minX), "z1", String.valueOf(minZ),
                    "x2", String.valueOf(maxX), "z2", String.valueOf(maxZ)));
        }

        logger.info(lang.getMessage("repair.starting_log", "count", String.valueOf(totalChunks)));

        CountDownLatch latch = new CountDownLatch(1);
        final int[] repaired = { 0 };

        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            try {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (world.isChunkGenerated(x, z)) {
                            // 加载区块
                            Chunk chunk = world.getChunkAt(x, z);

                            // 方法 1: 标记区块需要保存（强制重新计算高度图）
                            chunk.setForceLoaded(true);
                            chunk.setForceLoaded(false);

                            // 方法 2: 卸载并重新加载区块（强制保存）
                            world.unloadChunk(chunk);
                            world.loadChunk(x, z);

                            repaired[0]++;

                            if (repaired[0] % 100 == 0) {
                                logger.info(lang.getMessage("repair.progress_log", "done", String.valueOf(repaired[0]),
                                        "total", String.valueOf(totalChunks)));
                            }
                        }
                    }
                }

                logger.info(lang.getMessage("repair.complete_log", "count", String.valueOf(repaired[0])));
                if (sender != null) {
                    sender.sendMessage(lang.getMessage("repair.complete_msg", "count", String.valueOf(repaired[0])));
                    sender.sendMessage(lang.getMessage("repair.restart_msg"));
                }
            } catch (Exception e) {
                logger.severe(lang.getMessage("repair.error_log", "error", e.getMessage()));
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private org.bukkit.plugin.Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("FAWEReplace");
    }
}
