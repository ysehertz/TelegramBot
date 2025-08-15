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

import java.util.Set;

/**
 * ClassName: MessageTask
 * Package: com.bot.aabot.task
 * Description: 优化的消息处理任务，支持按sessionId分队列管理和背压控制
 *
 * @author fuchen
 * @version 2.0
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
        int totalProcessedCount = 0;
        int processedSessionCount = 0;

        try {
            LoggingUtils.logOperation("MESSAGE_TASK_START", "SYSTEM",
                String.format("开始消息处理任务 - 队列状态: %s", MessageContext.getQueueStatus()));

            // 获取所有session列表
            Set<String> sessionIds = MessageContext.getAllSessionIds();
            
            if (sessionIds.isEmpty()) {
                return; // 没有消息需要处理
            }

            LoggingUtils.logOperation("MESSAGE_TASK_SESSIONS", "SYSTEM",
                String.format("发现 %d 个session需要处理", sessionIds.size()));

            // 处理所有session的消息
            for (String sessionId : sessionIds) {
                int sessionProcessedCount = processSessionMessages(sessionId);
                if (sessionProcessedCount > 0) {
                    totalProcessedCount += sessionProcessedCount;
                    processedSessionCount++;
                    
                    LoggingUtils.logOperation("SESSION_PROCESS_COMPLETE", sessionId,
                        String.format("Session处理完成 - 处理消息数: %d", sessionProcessedCount));
                }
            }

            // 记录任务执行情况
            long taskDuration = System.currentTimeMillis() - taskStartTime;
            if (totalProcessedCount > 0 || taskDuration > 1000) {
                LoggingUtils.logOperation("MESSAGE_TASK_COMPLETE", "SYSTEM",
                    String.format("消息任务完成 - 处理session数: %d, 总处理消息数: %d, 耗时: %dms, 队列剩余: %d",
                        processedSessionCount, totalProcessedCount, taskDuration, MessageContext.getTotalQueueSize()));
            }

            // 性能监控
            LoggingUtils.logPerformance("messageTask", taskStartTime);

            // 队列状态监控
            monitorQueueHealth();
            
            // 清理空队列
            cleanupEmptyQueues();

        } catch (Exception e) {
            LoggingUtils.logError("MESSAGE_TASK_ERROR", "消息处理任务执行失败", e);
        }
    }
    
    /**
     * 处理单个session的消息
     * @param sessionId session标识
     * @return 处理的消息数量
     */
    private int processSessionMessages(String sessionId) {
        int processedCount = 0;
        int batchCount = 0;
        
        try {
            // 批量处理该session的消息
            while (MessageContext.getSessionQueueSize(sessionId) > 0 && batchCount < batchSize) {
                TextMessageEntity messageEntity = MessageContext.pollMessage(sessionId);
                if (messageEntity == null) {
                    break; // 队列已空
                }

                try {
                    // 检查消息是否超时（30分钟）
                    long currentTime = System.currentTimeMillis() / 1000;
                    long sendTime = Long.parseLong(messageEntity.getSendTime());

                    if (currentTime - sendTime >= 30 * 60) {
                        // 消息已超时，处理逻辑
                        if (messageEntity.isQuestion() && !gptService.isBeAnswered(sessionId)) {
                            sqlService.aitMessage(messageEntity);
                            LoggingUtils.logOperation("TIMEOUT_MESSAGE_PROCESSED",
                                String.valueOf(messageEntity.getUpdate().getMessage().getFrom().getId()),
                                String.format("处理超时消息 Session[%s]: %s", sessionId, messageEntity.getContent()));
                        }
                        processedCount++;
                    } else {
                        // 消息未超时，重新放回队首保持时间顺序
                        if (!MessageContext.offerFirstMessage(sessionId, messageEntity)) {
                            LoggingUtils.logError("MESSAGE_REQUEUE_FAILED", 
                                String.format("Session[%s]消息重新入队失败", sessionId), null);
                        }
                        LoggingUtils.logOperation("MESSAGE_NOT_TIMEOUT",
                            String.valueOf(messageEntity.getUpdate().getMessage().getFrom().getId()),
                            String.format("Session[%s]消息未超时，重新入队 - 剩余时间: %d秒",
                                sessionId, 30 * 60 - (currentTime - sendTime)));
                        break; // 既然这条消息未超时，该session后面的消息也不会超时（FIFO队列）
                    }
                } catch (Exception e) {
                    LoggingUtils.logError("MESSAGE_PROCESS_ERROR", 
                        String.format("Session[%s]处理单条消息失败", sessionId), e);
                    processedCount++; // 即使失败也计入处理数量，避免无限重试
                }

                batchCount++;
            }
        } catch (Exception e) {
            LoggingUtils.logError("SESSION_PROCESS_ERROR", 
                String.format("Session[%s]处理失败", sessionId), e);
        }
        
        return processedCount;
    }
    
    /**
     * 清理空的session队列
     */
    private void cleanupEmptyQueues() {
        try {
            int cleanedCount = MessageContext.cleanupEmptySessionQueues();
            if (cleanedCount > 0) {
                LoggingUtils.logOperation("QUEUE_CLEANUP", "SYSTEM",
                    String.format("清理了 %d 个空的session队列", cleanedCount));
            }
        } catch (Exception e) {
            LoggingUtils.logError("QUEUE_CLEANUP_ERROR", "清理空队列失败", e);
        }
    }
    
    /**
     * 队列健康状况监控
     */
    private void monitorQueueHealth() {
        int totalSize = MessageContext.getTotalQueueSize();
        int maxSize = MessageContext.getMaxQueueSize();
        int sessionCount = MessageContext.getSessionCount();
        double usageRate = (double) totalSize / maxSize;
        
        // 队列使用率告警
        if (usageRate > 0.8) {
            LoggingUtils.logError("QUEUE_HIGH_USAGE", 
                String.format("总队列使用率过高: %.2f%% (%d/%d), session数量: %d", 
                    usageRate * 100, totalSize, maxSize, sessionCount), null);
        } else if (usageRate > 0.6) {
            LoggingUtils.logOperation("QUEUE_WARNING", "SYSTEM", 
                String.format("总队列使用率较高: %.2f%% (%d/%d), session数量: %d", 
                    usageRate * 100, totalSize, maxSize, sessionCount));
        }
        
        // session数量监控
        if (sessionCount > 100) {
            LoggingUtils.logOperation("SESSION_COUNT_WARNING", "SYSTEM",
                String.format("Session数量较多: %d, 建议检查是否有僵尸session", sessionCount));
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
        if(MessageContext.getTotalQueueSize() > 50)
            try {
                String status = MessageContext.getDetailedQueueStatus();
                LoggingUtils.logSystemStatus("详细队列状态报告:\n" + status);

                // 如果有丢弃的消息，可以考虑重置计数器（取决于业务需求）
                if (MessageContext.getDroppedMessageCount() > 100) {
                    LoggingUtils.logOperation("QUEUE_MAINTENANCE", "SYSTEM",
                        "丢弃消息数量较多，建议检查系统负载或调整队列容量");
                }
            } catch (Exception e) {
                LoggingUtils.logError("QUEUE_STATUS_REPORT_ERROR", "队列状态报告失败", e);
            }
    }
    
    /**
     * 定期清理空队列 - 每30分钟执行一次
     */
    @Scheduled(fixedRate = 1800000) // 30分钟
    public void scheduledQueueCleanup() {
        try {
            int cleanedCount = MessageContext.cleanupEmptySessionQueues();
            if (cleanedCount > 0) {
                LoggingUtils.logOperation("SCHEDULED_QUEUE_CLEANUP", "SYSTEM",
                    String.format("定期清理了 %d 个空的session队列", cleanedCount));
            }
        } catch (Exception e) {
            LoggingUtils.logError("SCHEDULED_QUEUE_CLEANUP_ERROR", "定期清理空队列失败", e);
        }
    }
}
