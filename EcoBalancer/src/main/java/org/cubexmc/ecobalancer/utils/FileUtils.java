package org.cubexmc.ecobalancer.utils;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * 文件操作工具类
 */
public class FileUtils {
    
    /**
     * 初始化日志系统
     * @param plugin 插件实例
     * @param fileLogger 文件日志记录器
     * @return 文件处理器
     */
    public static FileHandler initializeLogger(JavaPlugin plugin, Logger fileLogger) {
        // 创建日志目录
        File logDir = new File(plugin.getDataFolder() + File.separator + "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        
        // 删除锁文件（如果存在）
        File lockFile = new File(plugin.getDataFolder() + File.separator + "logs" + File.separator + "latest.log.lck");
        if (lockFile.exists()) {
            lockFile.delete();
        }
        
        // 压缩已存在的日志文件
        File existingLogFile = new File(plugin.getDataFolder() + File.separator + "logs" + File.separator + "latest.log");
        if (existingLogFile.exists()) {
            compressLogFile(existingLogFile, plugin.getLogger());
        }
        
        // 创建新的文件处理器
        try {
            FileHandler fileHandler = new FileHandler(plugin.getDataFolder() + File.separator + "logs" + File.separator + "latest.log", true);
            fileHandler.setFormatter(new SimpleFormatter());
            fileLogger.addHandler(fileHandler);
            fileLogger.setUseParentHandlers(false);
            return fileHandler;
        } catch (IOException e) {
            plugin.getLogger().severe("无法创建日志文件处理器: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 压缩日志文件
     * @param logFile 要压缩的日志文件
     * @param logger 日志记录器
     */
    public static void compressLogFile(File logFile, Logger logger) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HHmm");
        String timestamp = dateFormat.format(new Date(logFile.lastModified()));
        File renamedLogFile = new File(logFile.getParent(), timestamp + ".log");
        
        // 重命名文件，加上时间戳
        if (!logFile.renameTo(renamedLogFile)) {
            logger.severe("无法重命名日志文件");
            return;
        }
        
        // 压缩重命名后的文件为.gz文件
        File compressedFile = new File(renamedLogFile.getParent(), renamedLogFile.getName() + ".gz");
        try (GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(compressedFile))) {
            Files.copy(renamedLogFile.toPath(), gzos);
        } catch (IOException e) {
            logger.severe("无法压缩日志文件: " + e.getMessage());
        }
        
        // 删除原始（已重命名）的日志文件
        if (!renamedLogFile.delete()) {
            logger.severe("压缩后无法删除原始日志文件");
        }
    }
    
    /**
     * 关闭并清理日志系统
     * @param fileHandler 文件处理器
     * @param fileLogger 文件日志记录器
     * @param plugin 插件实例
     */
    public static void cleanupLogger(FileHandler fileHandler, Logger fileLogger, JavaPlugin plugin) {
        if (fileHandler != null) {
            fileHandler.flush();
            fileLogger.removeHandler(fileHandler);
            fileHandler.close();
        }
        
        // 压缩最后的日志文件
        File logFile = new File(plugin.getDataFolder() + File.separator + "logs" + File.separator + "latest.log");
        if (logFile.exists()) {
            compressLogFile(logFile, plugin.getLogger());
        }
    }
    
    /**
     * 确保目录存在，如果不存在则创建
     * @param directoryPath 目录路径
     * @return 是否确保成功
     */
    public static boolean ensureDirectoryExists(String directoryPath) {
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            return directory.mkdirs();
        }
        return true;
    }
} 