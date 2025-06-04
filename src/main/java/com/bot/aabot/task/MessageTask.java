package com.bot.aabot.task;

import com.bot.aabot.MyAmazingBot;
import com.bot.aabot.service.GPTService;
import com.bot.aabot.service.SqlService;
import com.bot.aabot.utils.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bot.aabot.context.MessageContext;
import com.bot.aabot.entity.TextMessageEntity;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * ClassName: MessageTask
 * Package: com.bot.aabot.task
 * Description: 优化的消息处理任务，支持线程安全队列和背压控制
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/1
 */
@Component
@Slf4j
@EnableScheduling
public class MessageTask {
    @Autowired
    MyAmazingBot myAmazingBot;
    @Autowired
    GPTService gptService;
    @Autowired
    SqlService sqlService;
    
    @Value("${bot.message-queue.poll-interval:60}")
    private int pollInterval;
    
    @Value("${bot.message-queue.batch-size:10}")
    private int batchSize;

    /**
     * 消息处理任务 - 使用可配置的轮询间隔
     */
    @Scheduled(fixedDelayString = "${bot.message-queue.poll-interval:60}000") // 转换为毫秒
    public void messageTask() {
        long taskStartTime = System.currentTimeMillis();
        int processedCount = 0;
        int batchCount = 0;
        
        try {
            LoggingUtils.logOperation("MESSAGE_TASK_START", "SYSTEM", 
                String.format("开始消息处理任务 - 队列状态: %s", MessageContext.getQueueStatus()));
            
            // 批量处理消息，避免单次处理过多消息导致任务执行时间过长
            while (MessageContext.getQueueSize() > 0 && batchCount < batchSize) {
                TextMessageEntity messageEntity = MessageContext.pollMessage();
                if (messageEntity == null) {
                    break; // 队列已空
                }
                
                try {
                    // 检查消息是否超时（30分钟）
                    long currentTime = System.currentTimeMillis() / 1000;
                    long sendTime = Long.parseLong(messageEntity.getSendTime());
                    
                    if (currentTime - sendTime >= 30 * 60) {
                        // 消息已超时，处理逻辑
                        if (messageEntity.isQuestion() && !gptService.isBeAnswered()) {
                            sqlService.directResMessage(messageEntity);
                            LoggingUtils.logOperation("TIMEOUT_MESSAGE_PROCESSED", 
                                String.valueOf(messageEntity.getUpdate().getMessage().getFrom().getId()), 
                                "处理超时消息: " + messageEntity.getContent());
                        }
                        processedCount++;
                    } else {
                        // 消息未超时，重新放回队首保持时间顺序
                        if (!MessageContext.offerFirstMessage(messageEntity)) {
                            LoggingUtils.logError("MESSAGE_REQUEUE_FAILED", "消息重新入队失败", null);
                        }
                        LoggingUtils.logOperation("MESSAGE_NOT_TIMEOUT", 
                            String.valueOf(messageEntity.getUpdate().getMessage().getFrom().getId()), 
                            String.format("消息未超时，重新入队 - 剩余时间: %d秒", 
                                30 * 60 - (currentTime - sendTime)));
                        break; // 既然这条消息未超时，后面的消息也不会超时（FIFO队列）
                    }
                } catch (Exception e) {
                    LoggingUtils.logError("MESSAGE_PROCESS_ERROR", "处理单条消息失败", e);
                    processedCount++; // 即使失败也计入处理数量，避免无限重试
                }
                
                batchCount++;
            }
            
            // 记录任务执行情况
            long taskDuration = System.currentTimeMillis() - taskStartTime;
            if (processedCount > 0 || taskDuration > 1000) {
                LoggingUtils.logOperation("MESSAGE_TASK_COMPLETE", "SYSTEM", 
                    String.format("消息任务完成 - 处理消息数: %d, 耗时: %dms, 队列剩余: %d", 
                        processedCount, taskDuration, MessageContext.getQueueSize()));
            }
            
            // 性能监控
            LoggingUtils.logPerformance("messageTask", taskStartTime);
            
            // 队列状态监控
            monitorQueueHealth();
            
        } catch (Exception e) {
            LoggingUtils.logError("MESSAGE_TASK_ERROR", "消息处理任务执行失败", e);
        }
    }
    
    /**
     * 队列健康状况监控
     */
    private void monitorQueueHealth() {
        int currentSize = MessageContext.getQueueSize();
        int maxSize = MessageContext.getMaxQueueSize();
        double usageRate = (double) currentSize / maxSize;
        
        // 队列使用率告警
        if (usageRate > 0.8) {
            LoggingUtils.logError("QUEUE_HIGH_USAGE", 
                String.format("队列使用率过高: %.2f%% (%d/%d)", usageRate * 100, currentSize, maxSize), null);
        } else if (usageRate > 0.6) {
            LoggingUtils.logOperation("QUEUE_WARNING", "SYSTEM", 
                String.format("队列使用率较高: %.2f%% (%d/%d)", usageRate * 100, currentSize, maxSize));
        }
        
        // 丢弃消息监控
        int droppedCount = MessageContext.getDroppedMessageCount();
        if (droppedCount > 0) {
            LoggingUtils.logOperation("QUEUE_DROPPED_MESSAGES", "SYSTEM", 
                String.format("累计丢弃消息数: %d", droppedCount));
        }
    }
    
    /**
     * 队列状态报告任务 - 每5分钟执行一次
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void queueStatusReport() {
        try {
            String status = MessageContext.getQueueStatus();
            LoggingUtils.logSystemStatus("队列状态报告: " + status);
            
            // 如果有丢弃的消息，可以考虑重置计数器（取决于业务需求）
            if (MessageContext.getDroppedMessageCount() > 100) {
                LoggingUtils.logOperation("QUEUE_MAINTENANCE", "SYSTEM", 
                    "丢弃消息数量较多，建议检查系统负载或调整队列容量");
            }
        } catch (Exception e) {
            LoggingUtils.logError("QUEUE_STATUS_REPORT_ERROR", "队列状态报告失败", e);
        }
    }
}
