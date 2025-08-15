package com.bot.aabot.context;

import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.utils.LoggingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * ClassName: MessageContext
 * Package: com.bot.aabot.context
 * Description: 线程安全的消息上下文管理，支持按sessionId分队列管理和背压控制
 *
 * @author fuchen
 * @version 2.0
 * @createTime 2025/4/1
 */
@Component
public class MessageContext {
    // 使用ConcurrentHashMap管理多个session队列，每个sessionId对应一个独立的消息队列
    private static final ConcurrentHashMap<String, ConcurrentLinkedDeque<TextMessageEntity>> sessionQueues = new ConcurrentHashMap<>();
    
    // 队列大小限制（可配置）
    private static int maxQueueSize = 1000;
    
    // 单个session队列的最大大小
    private static int maxSessionQueueSize = 100;
    
    // 当前总队列大小计数器（原子操作保证线程安全）
    private static final AtomicInteger totalQueueSize = new AtomicInteger(0);
    
    // 丢弃消息计数器
    private static final AtomicInteger droppedMessageCount = new AtomicInteger(0);
    
    /**
     * 设置队列最大容量（通过配置文件注入）
     */
    @Value("${bot.message-queue.max-size:1000}")
    public void setMaxQueueSize(int maxSize) {
        maxQueueSize = maxSize;
        LoggingUtils.logSystemStatus("消息队列最大容量设置为: " + maxSize);
    }
    
    /**
     * 设置单个session队列最大容量
     */
    @Value("${bot.message-queue.max-session-size:100}")
    public void setMaxSessionQueueSize(int maxSessionSize) {
        maxSessionQueueSize = maxSessionSize;
        LoggingUtils.logSystemStatus("单个session队列最大容量设置为: " + maxSessionSize);
    }
    
    /**
     * 添加消息到指定session队列尾部（带背压控制）
     * @param sessionId session标识
     * @param message 消息实体
     * @return 是否成功添加
     */
    public static boolean offerMessage(String sessionId, TextMessageEntity message) {
        // 检查总队列是否已满
        if (totalQueueSize.get() >= maxQueueSize) {
            int droppedCount = droppedMessageCount.incrementAndGet();
            LoggingUtils.logError("MESSAGE_QUEUE_FULL", 
                String.format("总消息队列已满(当前大小: %d, 最大容量: %d)，丢弃消息，累计丢弃: %d", 
                    totalQueueSize.get(), maxQueueSize, droppedCount), null);
            return false;
        }
        
        // 获取或创建session队列
        ConcurrentLinkedDeque<TextMessageEntity> sessionQueue = sessionQueues.computeIfAbsent(sessionId, 
            k -> {
                LoggingUtils.logOperation("SESSION_QUEUE_CREATE", sessionId, "创建新的session消息队列");
                return new ConcurrentLinkedDeque<>();
            });
        
        // 检查单个session队列是否已满
        if (sessionQueue.size() >= maxSessionQueueSize) {
            int droppedCount = droppedMessageCount.incrementAndGet();
            LoggingUtils.logError("SESSION_QUEUE_FULL", 
                String.format("Session[%s]队列已满(当前大小: %d, 最大容量: %d)，丢弃消息，累计丢弃: %d", 
                    sessionId, sessionQueue.size(), maxSessionQueueSize, droppedCount), null);
            return false;
        }
        
        // 尝试添加消息到session队列尾部
        if (sessionQueue.offerLast(message)) {
            totalQueueSize.incrementAndGet();
            LoggingUtils.logOperation("MESSAGE_ENQUEUE", 
                String.valueOf(message.getUpdate().getMessage().getFrom().getId()), 
                String.format("消息入队成功 Session[%s]，当前session队列大小: %d, 总队列大小: %d", 
                    sessionId, sessionQueue.size(), totalQueueSize.get()));
            return true;
        }
        return false;
    }
    
    /**
     * 添加消息到指定session队列头部（用于重新入队，保持时间顺序）
     * @param sessionId session标识
     * @param message 消息实体
     * @return 是否成功添加
     */
    public static boolean offerFirstMessage(String sessionId, TextMessageEntity message) {
        // 检查总队列是否已满
        if (totalQueueSize.get() >= maxQueueSize) {
            int droppedCount = droppedMessageCount.incrementAndGet();
            LoggingUtils.logError("MESSAGE_QUEUE_FULL", 
                String.format("总消息队列已满(当前大小: %d, 最大容量: %d)，重新入队失败，累计丢弃: %d", 
                    totalQueueSize.get(), maxQueueSize, droppedCount), null);
            return false;
        }
        
        // 获取session队列
        ConcurrentLinkedDeque<TextMessageEntity> sessionQueue = sessionQueues.get(sessionId);
        if (sessionQueue == null) {
            // 如果队列不存在，创建新队列并添加到头部
            sessionQueue = new ConcurrentLinkedDeque<>();
            sessionQueues.put(sessionId, sessionQueue);
            LoggingUtils.logOperation("SESSION_QUEUE_CREATE", sessionId, "创建新的session消息队列(重新入队)");
        }
        
        // 检查单个session队列是否已满
        if (sessionQueue.size() >= maxSessionQueueSize) {
            int droppedCount = droppedMessageCount.incrementAndGet();
            LoggingUtils.logError("SESSION_QUEUE_FULL", 
                String.format("Session[%s]队列已满(当前大小: %d, 最大容量: %d)，重新入队失败，累计丢弃: %d", 
                    sessionId, sessionQueue.size(), maxSessionQueueSize, droppedCount), null);
            return false;
        }
        
        // 尝试添加消息到session队列头部
        if (sessionQueue.offerFirst(message)) {
            totalQueueSize.incrementAndGet();
            LoggingUtils.logOperation("MESSAGE_REQUEUE", 
                String.valueOf(message.getUpdate().getMessage().getFrom().getId()), 
                String.format("消息重新入队成功 Session[%s]，当前session队列大小: %d, 总队列大小: %d", 
                    sessionId, sessionQueue.size(), totalQueueSize.get()));
            return true;
        }
        return false;
    }
    
    /**
     * 从指定session队列中取出消息
     * @param sessionId session标识
     * @return 消息实体，如果队列为空返回null
     */
    public static TextMessageEntity pollMessage(String sessionId) {
        ConcurrentLinkedDeque<TextMessageEntity> sessionQueue = sessionQueues.get(sessionId);
        if (sessionQueue == null) {
            return null;
        }
        
        TextMessageEntity message = sessionQueue.pollFirst();
        if (message != null) {
            totalQueueSize.decrementAndGet();
            LoggingUtils.logOperation("MESSAGE_DEQUEUE", 
                String.valueOf(message.getUpdate().getMessage().getFrom().getId()), 
                String.format("消息出队成功 Session[%s]，当前session队列大小: %d, 总队列大小: %d", 
                    sessionId, sessionQueue.size(), totalQueueSize.get()));
            
            // 如果session队列为空，清理该队列以防止内存泄漏
            if (sessionQueue.isEmpty()) {
                sessionQueues.remove(sessionId);
                LoggingUtils.logOperation("SESSION_QUEUE_CLEANUP", sessionId, "清理空的session队列");
            }
        }
        return message;
    }
    
    /**
     * 获取所有session的ID集合
     * @return session ID集合
     */
    public static Set<String> getAllSessionIds() {
        return sessionQueues.keySet();
    }
    
    /**
     * 获取指定session队列大小
     * @param sessionId session标识
     * @return 队列大小
     */
    public static int getSessionQueueSize(String sessionId) {
        ConcurrentLinkedDeque<TextMessageEntity> sessionQueue = sessionQueues.get(sessionId);
        return sessionQueue != null ? sessionQueue.size() : 0;
    }
    
    /**
     * 获取总队列大小
     * @return 总队列大小
     */
    public static int getTotalQueueSize() {
        return totalQueueSize.get();
    }
    
    /**
     * 获取session队列数量
     * @return session队列数量
     */
    public static int getSessionCount() {
        return sessionQueues.size();
    }
    
    /**
     * 获取队列当前大小（兼容旧方法）
     * @return 总队列大小
     */
    public static int getQueueSize() {
        return getTotalQueueSize();
    }
    
    /**
     * 获取队列最大容量
     * @return 最大容量
     */
    public static int getMaxQueueSize() {
        return maxQueueSize;
    }
    
    /**
     * 获取单个session队列最大容量
     * @return 单个session最大容量
     */
    public static int getMaxSessionQueueSize() {
        return maxSessionQueueSize;
    }
    
    /**
     * 获取累计丢弃的消息数量
     * @return 丢弃消息数量
     */
    public static int getDroppedMessageCount() {
        return droppedMessageCount.get();
    }
    
    /**
     * 重置丢弃消息计数器
     */
    public static void resetDroppedMessageCount() {
        droppedMessageCount.set(0);
        LoggingUtils.logSystemStatus("丢弃消息计数器已重置");
    }
    
    /**
     * 清理空的session队列
     * @return 清理的队列数量
     */
    public static int cleanupEmptySessionQueues() {
        AtomicInteger cleanedCount = new AtomicInteger(0);
        sessionQueues.entrySet().removeIf(entry -> {
            boolean isEmpty = entry.getValue().isEmpty();
            if (isEmpty) {
                cleanedCount.incrementAndGet();
                LoggingUtils.logOperation("SESSION_QUEUE_CLEANUP", entry.getKey(), "清理空的session队列");
            }
            return isEmpty;
        });
        return cleanedCount.get();
    }
    
    /**
     * 清理所有队列
     */
    public static void clearAllQueues() {
        int totalCleared = totalQueueSize.get();
        sessionQueues.clear();
        totalQueueSize.set(0);
        LoggingUtils.logSystemStatus("清理所有消息队列，共清理消息: " + totalCleared);
    }
    
    /**
     * 获取队列状态信息
     * @return 队列状态字符串
     */
    public static String getQueueStatus() {
        return String.format("队列状态 - 总大小: %d, 最大容量: %d, 使用率: %.2f%%, session数量: %d, 累计丢弃: %d", 
            totalQueueSize.get(), maxQueueSize, 
            (double) totalQueueSize.get() / maxQueueSize * 100,
            sessionQueues.size(),
            droppedMessageCount.get());
    }
    
    /**
     * 获取指定session的消息列表（用于分析，返回副本避免并发修改）
     * @param sessionId session标识
     * @return 消息列表副本，如果session不存在返回空列表
     */
    public static List<TextMessageEntity> getSessionMessages(String sessionId) {
        ConcurrentLinkedDeque<TextMessageEntity> sessionQueue = sessionQueues.get(sessionId);
        if (sessionQueue == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(sessionQueue);
    }
    
    /**
     * 获取详细的队列状态信息（包含每个session的状态）
     * @return 详细队列状态字符串
     */
    public static String getDetailedQueueStatus() {
        StringBuilder status = new StringBuilder();
        status.append(getQueueStatus()).append("\n");
        status.append("各Session队列详情:\n");
        
        sessionQueues.forEach((sessionId, queue) -> {
            status.append(String.format("  Session[%s]: %d条消息\n", sessionId, queue.size()));
        });
        
        return status.toString();
    }
}
 