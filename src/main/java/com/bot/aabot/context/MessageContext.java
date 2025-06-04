package com.bot.aabot.context;

import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.utils.LoggingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ClassName: MessageContext
 * Package: com.bot.aabot.context
 * Description: 线程安全的消息上下文管理，支持背压控制
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/1
 */
@Component
public class MessageContext {
    // 使用线程安全的双端队列替换ConcurrentLinkedQueue，支持队首和队尾操作
    public static final ConcurrentLinkedDeque<TextMessageEntity> messageContextQueue = new ConcurrentLinkedDeque<>();
    
    // 队列大小限制（可配置）
    private static int maxQueueSize = 1000;
    
    // 当前队列大小计数器（原子操作保证线程安全）
    private static final AtomicInteger queueSize = new AtomicInteger(0);
    
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
     * 添加消息到队列尾部（带背压控制）
     * @param message 消息实体
     * @return 是否成功添加
     */
    public static boolean offerMessage(TextMessageEntity message) {
        // 检查队列是否已满
        if (queueSize.get() >= maxQueueSize) {
            // 队列已满，触发背压机制，丢弃消息
            int droppedCount = droppedMessageCount.incrementAndGet();
            LoggingUtils.logError("MESSAGE_QUEUE_FULL", 
                String.format("消息队列已满(当前大小: %d, 最大容量: %d)，丢弃消息，累计丢弃: %d", 
                    queueSize.get(), maxQueueSize, droppedCount), null);
            return false;
        }
        
        // 尝试添加消息到队列尾部
        if (messageContextQueue.offerLast(message)) {
            queueSize.incrementAndGet();
            LoggingUtils.logOperation("MESSAGE_ENQUEUE", 
                String.valueOf(message.getUpdate().getMessage().getFrom().getId()), 
                String.format("消息入队成功，当前队列大小: %d", queueSize.get()));
            return true;
        }
        return false;
    }
    
    /**
     * 添加消息到队列头部（用于重新入队，保持时间顺序）
     * @param message 消息实体
     * @return 是否成功添加
     */
    public static boolean offerFirstMessage(TextMessageEntity message) {
        // 检查队列是否已满
        if (queueSize.get() >= maxQueueSize) {
            // 队列已满，触发背压机制，丢弃消息
            int droppedCount = droppedMessageCount.incrementAndGet();
            LoggingUtils.logError("MESSAGE_QUEUE_FULL", 
                String.format("消息队列已满(当前大小: %d, 最大容量: %d)，重新入队失败，累计丢弃: %d", 
                    queueSize.get(), maxQueueSize, droppedCount), null);
            return false;
        }
        
        // 尝试添加消息到队列头部
        if (messageContextQueue.offerFirst(message)) {
            queueSize.incrementAndGet();
            LoggingUtils.logOperation("MESSAGE_REQUEUE", 
                String.valueOf(message.getUpdate().getMessage().getFrom().getId()), 
                String.format("消息重新入队成功，当前队列大小: %d", queueSize.get()));
            return true;
        }
        return false;
    }
    
    /**
     * 从队列中取出消息
     * @return 消息实体，如果队列为空返回null
     */
    public static TextMessageEntity pollMessage() {
        TextMessageEntity message = messageContextQueue.pollFirst();
        if (message != null) {
            queueSize.decrementAndGet();
            LoggingUtils.logOperation("MESSAGE_DEQUEUE", 
                String.valueOf(message.getUpdate().getMessage().getFrom().getId()), 
                String.format("消息出队成功，当前队列大小: %d", queueSize.get()));
        }
        return message;
    }
    
    /**
     * 获取队列当前大小
     * @return 队列大小
     */
    public static int getQueueSize() {
        return queueSize.get();
    }
    
    /**
     * 获取队列最大容量
     * @return 最大容量
     */
    public static int getMaxQueueSize() {
        return maxQueueSize;
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
     * 获取队列状态信息
     * @return 队列状态字符串
     */
    public static String getQueueStatus() {
        return String.format("队列状态 - 当前大小: %d, 最大容量: %d, 使用率: %.2f%%, 累计丢弃: %d", 
            queueSize.get(), maxQueueSize, 
            (double) queueSize.get() / maxQueueSize * 100, 
            droppedMessageCount.get());
    }
}
 