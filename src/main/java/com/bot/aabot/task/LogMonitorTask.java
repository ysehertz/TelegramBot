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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 日志监控定时任务
 * 定期监控日志状态并提供统计信息
 */
@Component
public class LogMonitorTask {
    private static final Logger logger = LoggerFactory.getLogger(LogMonitorTask.class);

    @Value("${logging.file.path:./logs}")
    private String logPath;
    
    private final Map<String, Long> lastSizeMap = new HashMap<>();
    private final Map<String, Integer> errorCountMap = new HashMap<>();
    
    /**
     * 每小时检查一次日志状态
     */
    @Scheduled(cron = "0 0 * * * ?") // 每小时执行一次
    public void checkLogStatus() {
        try {
            Path logDir = Paths.get(logPath);
            // 确保日志目录存在
            if (!Files.exists(logDir)) {
                logger.warn("日志目录不存在: {}", logDir.toAbsolutePath());
                return;
            }
            
            Map<String, Long> currentSizeMap = new HashMap<>();
            Map<String, Integer> errorCountInLastHour = new HashMap<>();
            
            // 统计日志文件大小和错误数量
            try (Stream<Path> paths = Files.list(logDir)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().endsWith(".log"))
                     .forEach(p -> {
                         try {
                             String fileName = p.getFileName().toString();
                             long fileSize = Files.size(p);
                             currentSizeMap.put(fileName, fileSize);
                             
                             // 统计最近一小时内的错误数量
                             if (fileName.equals("error.log")) {
                                 int errorCount = countErrorsInLastHour(p);
                                 errorCountInLastHour.put(fileName, errorCount);
                             }
                         } catch (IOException e) {
                             logger.error("获取日志文件大小失败: {}", p, e);
                         }
                     });
            }
            
            // 计算增长情况
            StringBuilder report = new StringBuilder();
            report.append("日志状态报告 - ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            
            currentSizeMap.forEach((fileName, currentSize) -> {
                Long lastSize = lastSizeMap.getOrDefault(fileName, 0L);
                long growthSize = currentSize - lastSize;
                double growthMB = growthSize / (1024.0 * 1024.0);
                
                report.append(fileName)
                      .append(": 当前大小 ").append(formatFileSize(currentSize))
                      .append(", 增长 ").append(String.format("%.2f MB", growthMB));
                
                // 添加错误计数信息
                if (fileName.equals("error.log")) {
                    Integer errorCount = errorCountInLastHour.getOrDefault(fileName, 0);
                    report.append(", 最近一小时错误数: ").append(errorCount);
                }
                
                report.append("\n");
            });
            
            // 更新上次大小记录
            lastSizeMap.putAll(currentSizeMap);
            // 更新错误计数
            errorCountMap.putAll(errorCountInLastHour);
            
            // 记录报告
            String reportStr = report.toString();
            logger.info(reportStr);
            LoggingUtils.logSystemStatus("日志监控报告:\n" + reportStr);
            
            // 检查错误报警
            errorCountInLastHour.forEach((fileName, count) -> {
                if (count > 50) { // 如果一小时内错误超过50个
                    String alertMsg = "警告: " + fileName + " 在最近一小时内有 " + count + " 个错误，请检查系统";
                    logger.warn(alertMsg);
                    LoggingUtils.logSystemStatus(alertMsg);
                }
            });
            
            // 检查日志文件大小报警
            currentSizeMap.forEach((fileName, size) -> {
                if (size > 100 * 1024 * 1024) { // 如果日志文件大于100MB
                    String alertMsg = "警告: " + fileName + " 大小已超过 100MB，请考虑清理";
                    logger.warn(alertMsg);
                    LoggingUtils.logSystemStatus(alertMsg);
                }
            });
            
        } catch (Exception e) {
            logger.error("检查日志状态失败", e);
        }
    }
    
    /**
     * 统计最近一小时内的错误数量
     */
    private int countErrorsInLastHour(Path errorLogFile) {
        try {
            // 获取一小时前的时间字符串格式
            String hourAgo = LocalDateTime.now().minusHours(1)
                                          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:"));
            
            // 读取文件并统计包含一小时前时间戳的行数
            try (Stream<String> lines = Files.lines(errorLogFile)) {
                return (int) lines.filter(line -> line.contains(hourAgo)).count();
            }
        } catch (IOException e) {
            logger.error("统计错误日志失败", e);
            return 0;
        }
    }
    
    /**
     * 格式化文件大小为可读形式
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
} 