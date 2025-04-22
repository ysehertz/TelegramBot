package com.bot.aabot.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class LoggingUtils {
    // 错误计数器
    private static final Map<String, AtomicInteger> errorCounters = new ConcurrentHashMap<>();
    // 错误阈值，超过此值将触发告警
    private static final int ERROR_THRESHOLD = 10;
    // 错误重置时间（毫秒）
    private static final long ERROR_RESET_TIME = 3600000; // 1小时
    
    // 日期时间格式化器
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    // 日志文件路径
    private static String logFilePath = "./logs";
    
    // 设置日志文件路径
    @Value("${logging.file.path:./logs}")
    public void setLogFilePath(String path) {
        logFilePath = path;
        // 确保日志目录存在
        try {
            Files.createDirectories(Paths.get(logFilePath));
        } catch (IOException e) {
            log.error("创建日志目录失败: {}", e.getMessage());
        }
    }

    /**
     * 记录错误日志并处理异常
     * @param errorType 错误类型
     * @param message 错误消息
     * @param exception 异常对象
     */
    public static void logError(String errorType, String message, Exception exception) {
        // 更新错误计数器
        AtomicInteger counter = errorCounters.computeIfAbsent(errorType, k -> new AtomicInteger(0));
        int errorCount = counter.incrementAndGet();
        
        // 记录错误日志
        log.error("[{}] {} - 错误详情: {}", errorType, message, exception != null ? exception.getMessage() : "未知错误", exception);
        
        // 写入错误日志文件
        writeToFile("error", 
                String.format("[%s] %s - %s - %s", 
                        LocalDateTime.now().format(formatter),
                        errorType, 
                        message, 
                        exception != null ? exception.getMessage() : "未知错误"));
        
        // 检查是否超过阈值
        if (errorCount >= ERROR_THRESHOLD) {
            log.warn("[{}] 错误次数超过阈值({})，请检查系统状态", errorType, ERROR_THRESHOLD);
            // 写入告警日志
            writeToFile("alert", 
                    String.format("[%s] %s - 错误次数超过阈值(%d)，请检查系统状态", 
                            LocalDateTime.now().format(formatter),
                            errorType, 
                            ERROR_THRESHOLD));
        }
        
        // 重置计数器（如果超过重置时间）
        if (errorCount == 1) {
            new Thread(() -> {
                try {
                    Thread.sleep(ERROR_RESET_TIME);
                    counter.set(0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    /**
     * 记录操作日志
     * @param operation 操作类型
     * @param userId 用户ID
     * @param details 详细信息
     */
    public static void logOperation(String operation, String userId, String details) {
        log.info("[{}] 用户[{}] - {}", operation, userId, details);
        
        // 写入操作日志文件
        writeToFile("operation", 
                String.format("[%s] [%s] 用户[%s] - %s", 
                        LocalDateTime.now().format(formatter),
                        operation,
                        userId, 
                        details));
    }

    /**
     * 记录性能日志
     * @param operation 操作类型
     * @param startTime 开始时间
     */
    public static void logPerformance(String operation, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        if (duration > 1000) { // 只记录超过1秒的操作
            log.warn("[{}] 操作耗时: {}ms", operation, duration);
            
            // 写入性能日志文件
            writeToFile("performance", 
                    String.format("[%s] [%s] 操作耗时: %dms", 
                            LocalDateTime.now().format(formatter),
                            operation, 
                            duration));
        }
    }

    /**
     * 记录系统状态
     * @param status 状态信息
     */
    public static void logSystemStatus(String status) {
        log.info("系统状态: {}", status);
        
        // 写入系统日志文件
        writeToFile("system", 
                String.format("[%s] 系统状态: %s", 
                        LocalDateTime.now().format(formatter),
                        status));
    }
    
    /**
     * 将日志信息写入指定文件
     * @param logType 日志类型（文件名前缀）
     * @param content 日志内容
     */
    private static void writeToFile(String logType, String content) {
        Path logFile = Paths.get(logFilePath, logType + ".log");
        try {
            // 确保日志目录存在
            Files.createDirectories(logFile.getParent());
            
            // 写入日志内容（追加模式）
            Files.writeString(
                    logFile, 
                    content + System.lineSeparator(), 
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.error("写入日志文件失败: {} - {}", logFile, e.getMessage());
        }
    }
    
    /**
     * 记录关键业务日志
     * @param businessType 业务类型
     * @param userId 用户ID
     * @param details 详细信息
     */
    public static void logBusinessOperation(String businessType, String userId, String details) {
        String logMessage = String.format("[%s] 用户[%s] - %s", businessType, userId, details);
        log.info(logMessage);
        
        // 写入业务日志文件
        writeToFile("business", 
                String.format("[%s] %s", 
                        LocalDateTime.now().format(formatter),
                        logMessage));
    }
    
    /**
     * 记录安全相关日志
     * @param securityType 安全事件类型
     * @param userId 用户ID
     * @param details 详细信息
     */
    public static void logSecurityEvent(String securityType, String userId, String details) {
        String logMessage = String.format("[%s] 用户[%s] - %s", securityType, userId, details);
        log.warn(logMessage);
        
        // 写入安全日志文件
        writeToFile("security", 
                String.format("[%s] %s", 
                        LocalDateTime.now().format(formatter),
                        logMessage));
    }
} 