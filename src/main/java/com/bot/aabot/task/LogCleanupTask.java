package com.bot.aabot.task;

import com.bot.aabot.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 日志清理定时任务
 * 定期清理过期的日志文件
 */
@Component
public class LogCleanupTask {
    private static final Logger logger = LoggerFactory.getLogger(LogCleanupTask.class);

    @Value("${logging.file.path:./logs}")
    private String logPath;
    
    @Value("${logging.cleanup.days:30}")
    private int maxRetentionDays;
    
    /**
     * 每天凌晨3点执行清理任务
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupLogs() {
        try {
            Path logDir = Paths.get(logPath);
            // 确保日志目录存在
            if (!Files.exists(logDir)) {
                logger.warn("日志目录不存在: {}", logDir.toAbsolutePath());
                return;
            }
            
            // 获取当前时间
            LocalDateTime now = LocalDateTime.now();
            // 计算清理阈值日期
            LocalDateTime thresholdDate = now.minusDays(maxRetentionDays);
            
            logger.info("开始清理超过 {} 天的日志文件", maxRetentionDays);
            LoggingUtils.logSystemStatus("开始清理超过 " + maxRetentionDays + " 天的日志文件");
            
            List<Path> filesToDelete = new ArrayList<>();
            
            // 查找符合条件的日志文件
            try (Stream<Path> paths = Files.list(logDir)) {
                filesToDelete = paths.filter(Files::isRegularFile)
                                     .filter(p -> p.getFileName().toString().endsWith(".log"))
                                     .filter(p -> isOlderThan(p, thresholdDate))
                                     .collect(Collectors.toList());
            }
            
            // 清理文件
            int deletedCount = 0;
            long totalFreedSpace = 0;
            
            for (Path file : filesToDelete) {
                try {
                    long fileSize = Files.size(file);
                    Files.delete(file);
                    deletedCount++;
                    totalFreedSpace += fileSize;
                    logger.info("已删除过期日志文件: {}, 大小: {}", file.getFileName(), formatSize(fileSize));
                } catch (IOException e) {
                    logger.error("删除日志文件失败: {}", file, e);
                    LoggingUtils.logError("LOG_CLEANUP_ERROR", "删除日志文件失败: " + file.getFileName(), e);
                }
            }
            
            if (deletedCount > 0) {
                String resultMsg = String.format("日志清理完成: 共删除 %d 个文件, 释放空间 %s", 
                        deletedCount, formatSize(totalFreedSpace));
                logger.info(resultMsg);
                LoggingUtils.logSystemStatus(resultMsg);
            } else {
                logger.info("没有找到需要清理的过期日志文件");
                LoggingUtils.logSystemStatus("没有找到需要清理的过期日志文件");
            }
            
        } catch (Exception e) {
            logger.error("日志清理任务执行失败", e);
            LoggingUtils.logError("LOG_CLEANUP_ERROR", "日志清理任务执行失败", e);
        }
    }
    
    /**
     * 检查文件是否早于指定日期
     */
    private boolean isOlderThan(Path file, LocalDateTime date) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            Instant fileInstant = attrs.creationTime().toInstant();
            LocalDateTime fileDate = LocalDateTime.ofInstant(fileInstant, ZoneId.systemDefault());
            return fileDate.isBefore(date);
        } catch (IOException e) {
            logger.error("读取文件属性失败: {}", file, e);
            return false;
        }
    }
    
    /**
     * 格式化文件大小
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