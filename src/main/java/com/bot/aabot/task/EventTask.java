package com.bot.aabot.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import com.bot.aabot.dao.ScoreDao;
import com.bot.aabot.service.GPTService;
import com.bot.aabot.utils.LoggingUtils;

import java.time.LocalDate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import com.bot.aabot.utils.BotReplyUtil;
import java.util.*;
import java.time.format.DateTimeFormatter;

/**
 * ClassName: EventTask
 * Package: com.bot.aabot.task
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/5/23
 */
@Component
public class EventTask {
    @Autowired
    public ScoreDao scoreDao;
    @Autowired
    public GPTService gptService;
    @Autowired
    public JdbcTemplate jdbcTemplate;
    @Autowired
    public ObjectMapper objectMapper;


    /**
     * 每天凌晨1点执行消息总结工作
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void messageSummary() {
        // 获取当前日期
        LocalDate currentDate = LocalDate.now();
        // 计算前一日日期，用于获取昨日群聊消息进行总结
        LocalDate previousDate = currentDate.minusDays(1);
        String summaryTargetGroup = scoreDao.getAdminGroup();
        try {
            List<ScoreDao.GroupTopicMessage> groupTopicMessages = scoreDao.getGroupTopicMessagesByDate(previousDate);
            
            // 获取管理员用户名列表
            List<String> adminUserNames = scoreDao.getAdminUserNames();
            
            for (int i = 0; i < groupTopicMessages.size(); i++) {
                ScoreDao.GroupTopicMessage gtm = groupTopicMessages.get(i);
                LoggingUtils.logOperation("MESSAGE_SUMMARY_START", "system", "开始执行消息总结任务,群聊" + gtm.groupName);
                String messagesJson = objectMapper.writeValueAsString(gtm.messages);
                String adminNamesJson = objectMapper.writeValueAsString(adminUserNames);
                
                // 构建合并的JSON数据
                Map<String, Object> combinedData = new HashMap<>();
                combinedData.put("messages", gtm.messages);
                combinedData.put("adminUsers", adminUserNames);
                String combinedJson = objectMapper.writeValueAsString(combinedData);
                
                // 添加重试机制的AI总结
                LoggingUtils.logOperation("MESSAGE_SUMMARY_RETRY", "system", "开始执行消息AI总结任务,群聊" + gtm.groupName);
                String summaryRes = retryAISummary(combinedJson, "summary", 3);
                String userRes = retryAISummary(combinedJson, "user", 3);
                
                StringBuilder sb = new StringBuilder();
                sb.append(gtm.groupName);
                if (gtm.topicName != null && !"null".equals(gtm.topicName)) {
                    sb.append("-").append(gtm.topicName);
                }
                sb.append("\n消息总结：\n").append(summaryRes).append("\n用户总结：\n").append(userRes);
                SendMessage sendMessage = SendMessage.builder()
                        .chatId(summaryTargetGroup)
                        .text(sb.toString())
                        .build();
                BotReplyUtil.reply(sendMessage, null);
                
                // 在每两次总结之间添加2秒延时（除了最后一次）
                if (i < groupTopicMessages.size() - 1) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LoggingUtils.logError("MESSAGE_SUMMARY_SLEEP", "消息总结任务延时被中断", ie);
                    }
                }
            }
        } catch (Exception e) {
            LoggingUtils.logError("MESSAGE_SUMMARY_ERROR", "消息总结任务执行失败", e);
        }
    }
    
    /**
     * 带重试机制的AI总结方法
     * @param jsonStr 消息JSON字符串
     * @param type 总结类型 "summary"或"user"
     * @param maxRetries 最大重试次数
     * @return AI总结结果
     */
    private String retryAISummary(String jsonStr, String type, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String result = gptService.summarizeGroupMessages(jsonStr, type);
                // 检查返回结果是否表示失败
                if (result != null && !result.startsWith("AI总结失败")) {
                    return result;
                }
                LoggingUtils.logError("AI_SUMMARY_RETRY", 
                    String.format("AI总结失败，第%d次重试，类型: %s", attempt, type), null);
            } catch (Exception e) {
                LoggingUtils.logError("AI_SUMMARY_RETRY", 
                    String.format("AI总结异常，第%d次重试，类型: %s", attempt, type), e);
            }
            
            // 如果不是最后一次尝试，等待1秒再重试
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LoggingUtils.logError("AI_SUMMARY_RETRY_SLEEP", "AI总结重试延时被中断", ie);
                    break;
                }
            }
        }
        
        // 所有重试都失败后返回失败消息
        return String.format("AI总结失败：重试%d次后仍然失败，类型: %s", maxRetries, type);
    }

    /**
     * 每天凌晨1点半执行用户最终积分计算
     */
    @Scheduled(cron = "0 30 1 * * ?")
    public void calculateUserFinalPoints() {
        try {
            LoggingUtils.logOperation("CALCULATE_FINAL_POINTS_START", "system", "开始执行用户最终积分计算任务");
            
            // 1. 获取正在进行中和结束两天内的活动
            List<com.bot.aabot.entity.EventRecord> events = scoreDao.getActiveAndRecentEndedEvents();
            LoggingUtils.logOperation("CALCULATE_FINAL_POINTS_EVENTS", "system", 
                String.format("找到%d个需要计算积分的活动", events.size()));
            
            int totalUsersProcessed = 0;
            
            for (com.bot.aabot.entity.EventRecord event : events) {
                int eventId = event.getEventId();
                
                // 2. 获取该活动的所有用户积分记录
                List<Map<String, Object>> userPointsList = scoreDao.getAllUserPointsByEventId(eventId);
                
                for (Map<String, Object> userPoints : userPointsList) {
                    String userId = (String) userPoints.get("user_id");
                    Object pointsObj = userPoints.get("points");
                    Object specialPointsObj = userPoints.get("special_points");
                    
                    if (pointsObj == null || specialPointsObj == null) {
                        continue;
                    }
                    
                    int points = Integer.parseInt(pointsObj.toString());
                    int specialPoints = Integer.parseInt(specialPointsObj.toString());
                    
                    // 3. 获取用户的成就加成
                    double achievementBonus = scoreDao.getUserAchievementBonus(userId, eventId);
                    
                    // 4. 计算最终积分：points * 成就加成 + special_points
                    double aggregatePoints = points * achievementBonus + specialPoints;
                    
                    // 5. 更新用户最终积分
                    boolean updated = scoreDao.updateUserAggregatePoints(eventId, userId, aggregatePoints);
                    if (updated) {
                        totalUsersProcessed++;
                        LoggingUtils.logOperation("CALCULATE_FINAL_POINTS_USER", userId, 
                            String.format("活动%d最终积分计算完成: 基础积分=%d, 特殊积分=%d, 成就加成=%.2f, 最终积分=%.2f", 
                                eventId, points, specialPoints, achievementBonus, aggregatePoints));
                    } else {
                        LoggingUtils.logError("CALCULATE_FINAL_POINTS_UPDATE_FAILED", 
                            String.format("用户%s活动%d最终积分更新失败", userId, eventId), null);
                    }
                }
            }
            
            LoggingUtils.logOperation("CALCULATE_FINAL_POINTS_COMPLETE", "system", 
                String.format("用户最终积分计算任务完成，共处理%d个用户", totalUsersProcessed));
                
        } catch (Exception e) {
            LoggingUtils.logError("CALCULATE_FINAL_POINTS_ERROR", "用户最终积分计算任务执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 每周二下午3:20执行每周消息总结工作
     */
    @Scheduled(cron = "0 0 0 * * 5")
    public void weeklyMessageSummary() {
        LocalDate currentDate = LocalDate.now();
        LocalDate weekStartDate = currentDate.minusDays(7);
        String summaryTargetGroup = scoreDao.getAdminGroup();
        try {
            System.out.println();
            
            // 收集一周内的所有消息，按组和话题分组
            Map<String, List<Object>> weeklyGroupTopicMessages = new HashMap<>();
            Map<String, String> groupTopicNames = new HashMap<>();
            
            // 获取过去7天的消息
            for (int day = 0; day < 7; day++) {
                LocalDate targetDate = weekStartDate.plusDays(day);
                List<ScoreDao.GroupTopicMessage> dailyMessages = scoreDao.getGroupTopicMessagesByDate(targetDate);
                
                for (ScoreDao.GroupTopicMessage gtm : dailyMessages) {
                    String key = gtm.groupName + "-" + (gtm.topicName != null ? gtm.topicName : "default");
                    weeklyGroupTopicMessages.computeIfAbsent(key, k -> new ArrayList<>()).addAll(gtm.messages);
                    groupTopicNames.put(key, gtm.groupName + (gtm.topicName != null && !"null".equals(gtm.topicName) ? "-" + gtm.topicName : ""));
                }
            }
            
            // 获取管理员用户名列表
            List<String> adminUserNames = scoreDao.getAdminUserNames();
            
            int index = 0;
            for (Map.Entry<String, List<Object>> entry : weeklyGroupTopicMessages.entrySet()) {
                String key = entry.getKey();
                List<Object> messages = entry.getValue();
                
                if (messages.isEmpty()) {
                    continue;
                }
                
                // 构建合并的JSON数据
                Map<String, Object> combinedData = new HashMap<>();
                combinedData.put("messages", messages);
                combinedData.put("adminUsers", adminUserNames);
                String combinedJson = objectMapper.writeValueAsString(combinedData);
                
                // 添加重试机制的AI总结
                String summaryRes = retryAISummary(combinedJson, "summary", 3);
                String userRes = retryAISummary(combinedJson, "user", 3);
                
                StringBuilder sb = new StringBuilder();
                sb.append("📅 每周总结 - ").append(groupTopicNames.get(key));
                sb.append("\n时间范围：").append(weekStartDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                  .append(" 至 ").append(currentDate.minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                sb.append("\n消息总结：\n").append(summaryRes).append("\n用户总结：\n").append(userRes);
                
                SendMessage sendMessage = SendMessage.builder()
                        .chatId(summaryTargetGroup)
                        .text(sb.toString())
                        .build();
                BotReplyUtil.reply(sendMessage, null);
                
                // 在每两次总结之间添加2秒延时（除了最后一次）
                if (index < weeklyGroupTopicMessages.size() - 1) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LoggingUtils.logError("WEEKLY_MESSAGE_SUMMARY_SLEEP", "每周消息总结任务延时被中断", ie);
                    }
                }
                index++;
            }
        } catch (Exception e) {
            LoggingUtils.logError("WEEKLY_MESSAGE_SUMMARY_ERROR", "每周消息总结任务执行失败", e);
        }
    }
}
