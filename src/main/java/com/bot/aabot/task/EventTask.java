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
    @Scheduled(cron = "0 0 1 * * ?")
    public void messageSummary() {
        LocalDate currentDate = LocalDate.now();
        LocalDate previousDate = currentDate.minusDays(1);
        String summaryTargetGroup = scoreDao.getAdminGroup();
        try {
            List<ScoreDao.GroupTopicMessage> groupTopicMessages = scoreDao.getGroupTopicMessagesByDate(previousDate);
            for (ScoreDao.GroupTopicMessage gtm : groupTopicMessages) {
                String jsonStr = objectMapper.writeValueAsString(gtm.messages);
                String summaryRes = gptService.summarizeGroupMessages(jsonStr, "summary");
                String userRes = gptService.summarizeGroupMessages(jsonStr, "user");
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
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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
}
