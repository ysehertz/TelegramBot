package com.bot.aabot.initializer;

import com.bot.aabot.utils.LoggingUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 日志目录初始化器
 * 在应用启动时自动创建日志目录
 */
@Component
public class LogDirectoryInitializer {

    @Value("${logging.file.path:./logs}")
    private String logPath;

    /**
     * 应用启动后初始化日志目录
     */
    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(logPath);
            // 如果目录不存在则创建
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                System.out.println("日志目录已创建: " + path.toAbsolutePath());
                LoggingUtils.logSystemStatus("日志目录已创建: " + path.toAbsolutePath());
            } else {
                System.out.println("日志目录已存在: " + path.toAbsolutePath());
                LoggingUtils.logSystemStatus("日志目录已存在: " + path.toAbsolutePath());
            }
            
            // 检查目录是否可写
            if (!Files.isWritable(path)) {
                System.err.println("警告: 日志目录不可写: " + path.toAbsolutePath());
                LoggingUtils.logError("LOG_DIR_ERROR", "日志目录不可写", new Exception("权限不足"));
            }
            
            // 检查磁盘空间
            checkDiskSpace(path);
        } catch (Exception e) {
            System.err.println("创建日志目录失败: " + e.getMessage());
            LoggingUtils.logError("LOG_DIR_ERROR", "创建日志目录失败", e);
        }
    }
    
    /**
     * 检查磁盘空间
     */
    private void checkDiskSpace(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            long totalSpace = store.getTotalSpace();
            long usableSpace = store.getUsableSpace();
            long usedSpace = totalSpace - usableSpace;
            
            // 计算已使用百分比
            double usedPercentage = (double) usedSpace / totalSpace * 100;
            
            String spaceInfo = String.format(
                "磁盘空间信息 - 总空间: %s, 已用空间: %s (%.2f%%), 可用空间: %s",
                formatSize(totalSpace),
                formatSize(usedSpace),
                usedPercentage,
                formatSize(usableSpace)
            );
            
            System.out.println(spaceInfo);
            LoggingUtils.logSystemStatus(spaceInfo);
            
            // 如果可用空间低于10%，发出警告
            if (usableSpace < 0.1 * totalSpace) {
                String warningMsg = String.format(
                    "警告: 磁盘空间不足! 可用空间仅剩 %.2f%%, 请尽快清理磁盘空间!",
                    ((double) usableSpace / totalSpace * 100)
                );
                System.err.println(warningMsg);
                LoggingUtils.logError("DISK_SPACE_LOW", warningMsg, new Exception("磁盘空间不足"));
            }
        } catch (Exception e) {
            System.err.println("检查磁盘空间失败: " + e.getMessage());
            LoggingUtils.logError("DISK_CHECK_ERROR", "检查磁盘空间失败", e);
        }
    }
    
    /**
     * 格式化文件大小显示
     */
    private String formatSize(long size) {
        final long KB = 1024;
        final long MB = KB * 1024;
        final long GB = MB * 1024;
        
        if (size >= GB) {
            return String.format("%.2f GB", (double) size / GB);
        } else if (size >= MB) {
            return String.format("%.2f MB", (double) size / MB);
        } else if (size >= KB) {
            return String.format("%.2f KB", (double) size / KB);
        } else {
            return size + " Bytes";
        }
    }
} 