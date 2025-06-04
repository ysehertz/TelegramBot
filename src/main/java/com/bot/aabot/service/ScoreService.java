package com.bot.aabot.service;

import com.bot.aabot.context.ConstructionEventContext;
import com.bot.aabot.dao.ScoreDao;
import com.bot.aabot.entity.EventAchievement;
import com.bot.aabot.entity.EventRecord;
import com.bot.aabot.entity.UserActivityLog;
import com.bot.aabot.entity.UserAchievement;
import com.bot.aabot.utils.LoggingUtils;
import com.bot.aabot.utils.BotReplyUtil;
import com.bot.aabot.utils.TimeFormatUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.telegram.telegrambots.abilitybots.api.objects.MessageContext;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.reactions.MessageReactionUpdated;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ClassName: ScoreService
 * Package: com.bot.aabot.service
 * Description: 处理用户积分和成就系统
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/27
 */

//@PropertySource("classpath:score-config.yml")
@Service
@ConfigurationProperties
public class ScoreService {


    @Autowired
    ScoreDao scoreDao;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private ApplicationContext applicationContext;
    
    // 积分配置
    private Map<String, Integer> points = new HashMap<>();
    
    // 冷却时间配置
    private Map<String, Object> cooldown = new HashMap<>();
    
    // 签到配置
    private Map<String, String> check_in = new HashMap<>();
    
    // 互动配置
    private Map<String, Object> interaction = new HashMap<>();
    
    // 长文本阈值
//    @Value("")
//    private int longTextThreshold;
    
//    // 长文本特殊积分
//    @Value("")
//    private int longTextSpecialPoints;
    
//    // 文档特殊积分
//    @Value("")
//    private int documentSpecialPoints;
//
    // 每条消息最大互动次数
    private int maxInteractionsPerMessage = 5;
    
    // 冷却时间缓存
    private Map<String, Long> cooldownCache = new HashMap<>();
    
    // 签到关键词集合
    private Set<String> checkInKeywords = new HashSet<>();
    
    // 日期格式化
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    @PostConstruct
    public void init() {
        // 初始化冷却时间缓存 Initial cooling time
        // 获取冷却时间配置 Obtain the configuration for cooling time
        Map<String, Object> messageTypes = (Map<String, Object>) cooldown.get("message_types");
        if (messageTypes != null) {
            for (Map.Entry<String, Object> entry : messageTypes.entrySet()) {
                cooldownCache.put(entry.getKey(), Long.valueOf(entry.getValue().toString()));
            }
        }
        
        // 初始化签到关键词 initialization of sign-in keywords 
        if (check_in.containsKey("keywords")) {
            String keywordsStr = check_in.get("keywords");
            if (keywordsStr != null && !keywordsStr.isEmpty()) {
                // 去除双引号，然后按逗号分割   remove double quotes and split by commas 
                keywordsStr = keywordsStr.replace("\"", "");
                String[] keywords = keywordsStr.split(",");
                checkInKeywords.addAll(Arrays.asList(keywords));
                LoggingUtils.logOperation("CHECK_IN_KEYWORDS", "SYSTEM", 
                        "签到关键词已加载: " + checkInKeywords);
            }
        }
        
        // 初始化最大互动次数 initialization of maximum interactions per message
        if (interaction.containsKey("max_interactions_per_message")) {
            Object maxValue = interaction.get("max_interactions_per_message");
            if (maxValue != null) {
                maxInteractionsPerMessage = Integer.valueOf(maxValue.toString());
            }
        }

        LoggingUtils.logOperation("SCORE_CONFIG", "SYSTEM", "积分系统配置已加载");
    }
    
    /**
     * 处理消息，分发到不同类型的处理方法
     * @param update Telegram更新对象
     */
    public void processMessage(Update update) {
        if (update.hasMessage()) {
            // 首先检查是否是消息引用回复 first check if it is a message reply 
            if (update.getMessage().isReply() && update.getMessage().getReplyToMessage().hasText()) {
                processMessageReply(update);
            }
            // 然后检查是否是签到消息 the check if it is a check-in message 
            else if (isCheckInMessage(update)) {
                processCheckIn(update);
            } else {
                // 如果不是特殊消息，按普通消息处理 if it is not a special message, process it as an ordinary message 
                processOrdinaryMessage(update);
            }
        } 
        // 处理表情回复 process the reaction message
        else if (update.getMessageReaction() != null) {
            processMessageReaction(update);
        }
        // 可以扩展其他类型的消息处理 can extend other types of message processing 
    }
    
    /**
     * 处理消息引用回复
     * @param update Telegram更新对象
     */
    private void processMessageReply(Update update) {
        try {
            Message message = update.getMessage();
            Message replyTo = message.getReplyToMessage();
            // 确保是群聊
            if (message.getChat().isGroupChat() || message.getChat().isSuperGroupChat()) {
                String groupId = String.valueOf(message.getChatId());
                String userId = String.valueOf(message.getFrom().getId());
                String targetUserId = String.valueOf(replyTo.getFrom().getId());
                Integer targetMessageId = replyTo.getMessageId();

                // 如果回复自己的消息，不计分
                if (userId.equals(targetUserId)) {
                    return;
                }

                // 记录开始处理时间
                long startTime = System.currentTimeMillis();
                LoggingUtils.logOperation("PROCESS_MESSAGE_REPLY", userId,
                        String.format("处理消息引用回复: 用户 %s 回复了用户 %s 的消息 %d",
                                userId, targetUserId, targetMessageId));

                // 只为全局成就记录一次用户活动日志
                UserActivityLog log = new UserActivityLog();
                log.setUserId(userId);
                log.setActivityType("message_reply");
                log.setActivityLog(message.getText());
                log.setEventId(0); // 全局日志
                log.setTopicId(message.getMessageThreadId());
                log.setChatId(groupId);
                scoreDao.addUserActivityLog(log);

                // 更新全局互动相关成就
                updateGlobalInteractionAchievements(groupId, targetUserId, "message_reply", userId);

                // 获取群聊相关的活动列表
                List<EventRecord> activeEvents = scoreDao.getActiveEventsByGroupId(groupId);
                for (EventRecord event : activeEvents) {
                    int eventId = event.getEventId();
                    // 只为每个活动添加基础分
                    int pointsToAdd = getInteractionPoints();
                    scoreDao.updateUserPoints(eventId, update.getMessage().getChat().getTitle(), userId, replyTo.getFrom().getUserName(), pointsToAdd, 0);
                }

                LoggingUtils.logPerformance("processMessageReply", startTime);
            }

            // 继续处理普通消息（回复也是一种普通消息）
            processOrdinaryMessage(update);

        } catch (Exception e) {
            LoggingUtils.logError("PROCESS_REPLY_ERROR", "处理消息引用回复失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理表情回复
     * @param update Telegram更新对象
     */
    private void processMessageReaction(Update update) {
        try {
            MessageReactionUpdated reaction = update.getMessageReaction();

            // 确保是群聊
            if (reaction.getChat().isGroupChat() || reaction.getChat().isSuperGroupChat()) {
                String groupId = String.valueOf(reaction.getChat().getId());
                String userId = String.valueOf(reaction.getUser().getId());
                Optional<Map<String, Object>> logInfoOpt = scoreDao.findUserIdAndTopicIdByMessageId(reaction.getMessageId());
                if (logInfoOpt.isEmpty()) {
                    return;
                }
                Map<String, Object> logInfo = logInfoOpt.get();
                String targetUserId = logInfo.get("user_id") != null ? logInfo.get("user_id").toString() : null;
                Integer targetMessageId = logInfo.get("topic_id") != null ? Integer.valueOf(logInfo.get("topic_id").toString()) : null;
                if (targetUserId == null || targetMessageId == null) {
                    return;
                }

                // 如果是对自己的消息添加表情，不计分
                if (userId.equals(targetUserId)) {
                    return;
                }

                // 只处理添加表情的情况，不处理移除表情
                if (reaction.getNewReaction() == null ) {
                    return;
                }

                long startTime = System.currentTimeMillis();
                LoggingUtils.logOperation("PROCESS_MESSAGE_REACTION", userId,
                        String.format("处理表情回复: 用户 %s 对用户 %s 的消息 %d 添加了表情",
                                userId, targetUserId, targetMessageId));

                // 只为全局成就记录一次用户活动日志
                UserActivityLog log = new UserActivityLog();
                log.setUserId(targetUserId);
                log.setActivityType("message_reaction");
                log.setActivityLog("");
                log.setEventId(0); // 全局日志
                log.setTopicId(targetMessageId);
                log.setChatId(groupId);
                scoreDao.addUserActivityLog(log);

                // 更新全局互动相关成就
                updateGlobalInteractionAchievements(groupId, targetUserId, "message_reaction", userId);

                // 获取群聊相关的活动列表
                List<EventRecord> activeEvents = scoreDao.getActiveEventsByGroupId(groupId);
                for (EventRecord event : activeEvents) {
                    int eventId = event.getEventId();
                    // 只为每个活动添加基础分
                    int pointsToAdd = getInteractionPoints();
                    scoreDao.updateUserPoints(eventId, update.getMessageReaction().getChat().getTitle(), userId, "未知用户", pointsToAdd, 0);
                }

                LoggingUtils.logPerformance("processMessageReaction", startTime);
            }
        } catch (Exception e) {
            LoggingUtils.logError("PROCESS_REACTION_ERROR", "处理表情回复失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取互动积分配置
     * @return 互动积分
     */
    private int getInteractionPoints() {
        Object interactionPoints = points.get("interaction");
        if (interactionPoints != null) {
            return Integer.valueOf(interactionPoints.toString());
        }
        return 2; // 如果没有配置，默认给2分
    }
    
    /**
     * 获取回复通知消息
     * @return 通知消息
     */
    private String getReplyNotificationMessage() {
        if (interaction.containsKey("reply_message")) {
            return interaction.get("reply_message").toString();
        }
        return ""; // 默认不发送通知
    }
    
    /**
     * 获取表情回复通知消息
     * @return 通知消息
     */
    private String getReactionNotificationMessage() {
        if (interaction.containsKey("reaction_message")) {
            return interaction.get("reaction_message").toString();
        }
        return ""; // 默认不发送通知
    }
    
    /**
     * 判断消息是否是签到消息
     * @param update Telegram更新对象
     * @return 是否是签到消息
     */
    private boolean isCheckInMessage(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText().trim();
            return checkInKeywords.contains(messageText);
        }
        return false;
    }

    /**
     * 处理签到消息
     *
     * @param update
     */
    private void processCheckIn(Update update) {
        try {
            Message message = update.getMessage();
            if (message.getChat().isGroupChat() || message.getChat().isSuperGroupChat()) {
                String groupId = String.valueOf(message.getChatId());
                String userId = String.valueOf(message.getFrom().getId());
                String userName = message.getFrom().getUserName();
                String messageText = message.hasText() ? message.getText().trim() : "";
    
                long startTime = System.currentTimeMillis();
                LoggingUtils.logOperation("PROCESS_CHECK_IN", userId, "开始处理用户签到: " + messageText);
    
                String today = LocalDateTime.now().format(dateFormatter);
    
                // 1. 判断用户在当前群聊是否已签到（不考虑活动）
                if (scoreDao.hasUserCheckedInToday(groupId, 0, userId)) {
                    String duplicateMessage = check_in.getOrDefault("duplicate_message",
                            "用户 {user_name} 今天已经签到过了哦~")
                            .replace("{user_name}", userName)
                            .replace("{date}", today);
                    sendMessageToGroup(groupId, duplicateMessage, update);
                    LoggingUtils.logOperation("CHECK_IN_DUPLICATE", userId,
                            "用户今日已签到: " + duplicateMessage);
                    return;
                }
    
                // 2. 记录签到并更新全局成就
                boolean recorded = scoreDao.recordUserCheckIn(groupId, 0, userId, messageText);
                if (recorded) {
                    LoggingUtils.logOperation("GLOBAL_CHECK_IN", userId, "记录全局签到成功");
                    updateGlobalAchievements(groupId, userId, "check_in", userName);
                    String successMessage = check_in.getOrDefault("success_message",
                            "用户 {user_name} 在 {date} 成功签到！")
                            .replace("{user_name}", userName)
                            .replace("{date}", today)
//                            .replace("{points}", "0")
                            ;
                    sendMessageToGroup(groupId, successMessage, update);
                }
    
                // 3. 遍历活动，仅为签到用户加基础分
                List<EventRecord> activeEvents = scoreDao.getActiveEventsByGroupId(groupId);
                int checkInPoints = getCheckInPoints();
                for (EventRecord event : activeEvents) {
                    int eventId = event.getEventId();
                    scoreDao.updateUserPoints(eventId, update.getMessage().getChat().getTitle(), userId, userName, checkInPoints, 0);
                }
    
                LoggingUtils.logPerformance("processCheckIn", startTime);
            }
        } catch (Exception e) {
            LoggingUtils.logError("PROCESS_CHECK_IN_ERROR", "处理签到失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 发送消息到群组
     * @param chatId 群组ID
     * @param text 消息内容
     * @param update 可选，若有则用于自动threadId
     */
    private void sendMessageToGroup(String chatId, String text, Update update) {
        try {
            SendMessage message = SendMessage
                    .builder()
                    .chatId(chatId)
                    .text(text)
                    .build();
            if (update != null) {
                BotReplyUtil.reply(message, update);
            } else {
                // 无update时直接发送
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
            }
        } catch (Exception e) {
            LoggingUtils.logError("SEND_MESSAGE_ERROR", "发送消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取签到积分配置
     * @return 签到积分
     */
    private int getCheckInPoints() {
        Object checkInPoints = points.get("check_in");
        if (checkInPoints != null) {
            return Integer.valueOf(checkInPoints.toString());
        }
        return 5; // 如果没有配置，默认给5分
    }

    /**
     * 处理普通消息
     * @param update Telegram更新对象
     */
    public void processOrdinaryMessage(Update update) {
        try {
            Message message = update.getMessage();
            // 只处理群聊消息
            if (message.getChat().isGroupChat() || message.getChat().isSuperGroupChat()) {
                String groupId = String.valueOf(message.getChatId());
                String userId = String.valueOf(message.getFrom().getId());
                String userName = (message != null && message.getFrom().getUserName() != null) ? message.getFrom().getUserName() : (message != null ? message.getFrom().getFirstName() : "");

                // 记录用户首次加入时间
                scoreDao.recordUserJoinTime(groupId, userId, groupId);

                // 获取消息类型
                String activityType = determineActivityType(message);

                // 只为全局成就记录一次用户活动日志
                UserActivityLog log = new UserActivityLog();
                log.setUserId(userId);
                log.setActivityType(activityType);
                log.setActivityLog(message.getText());
                log.setEventId(0); // 全局日志
                log.setTopicId(message.getMessageThreadId());
                log.setChatId(groupId);
                scoreDao.addUserActivityLog(log);

                // 更新用户全局成就
                updateGlobalAchievements(groupId, userId, activityType, userName);

                // 获取群聊相关的活动列表
                List<EventRecord> activeEvents = scoreDao.getActiveEventsByGroupId(groupId);
                for (EventRecord event : activeEvents) {
                    int eventId = event.getEventId();
                    // 只为每个活动添加基础分
                    int pointsToAdd = getDefaultPoints();
                    scoreDao.updateUserPoints(eventId, update.getMessage().getChat().getTitle(), userId, userName, pointsToAdd, 0);
                }
            }
        } catch (Exception e) {
            LoggingUtils.logError("PROCESS_MESSAGE_ERROR", "处理普通消息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 确定消息的活动类型
     * @param message Telegram消息
     * @return 活动类型
     */
    private String determineActivityType(Message message) {
        if (message.hasText()) {
            return "text_message";
        } else if (message.hasPhoto()) {
            return "photo_message";
        } else if (message.hasVideo()) {
            return "video_message";
        } else if (message.hasDocument()) {
            return "document_message";
        } else if (message.hasAudio()) {
            return "audio_message";
        } else if (message.hasSticker()) {
            return "sticker_message";
        } else {
            return "other_message";
        }
    }
    
    /**
     * 获取默认积分配置
     * @return 默认积分
     */
    private int getDefaultPoints() {
        Object defaultPoints = points.get("default");
        if (defaultPoints != null) {
            return Integer.valueOf(defaultPoints.toString());
        }
        return 1; // 如果没有配置，默认给1分
    }


    /**
     * 计算连续签到天数
     * @param checkInLogs 签到记录列表
     * @return 连续签到天数
     */
    private int calculateConsecutiveCheckInDays(List<UserActivityLog> checkInLogs) {
        if (checkInLogs.isEmpty()) {
            return 0;
        }
        
        // 按活动时间降序排序
        checkInLogs.sort((a, b) -> b.getActivityTime().compareTo(a.getActivityTime()));
        
        int consecutiveDays = 1; // 今天已经签到，从1开始计数
        LocalDateTime lastCheckIn = LocalDateTime.parse(checkInLogs.get(0).getActivityTime(), formatter);
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        
        // 如果最后一次签到不是今天，则连续签到已中断
        if (!lastCheckIn.toLocalDate().equals(today.toLocalDate())) {
            return 0;
        }
        
        // 检查过去的签到记录
        for (int i = 1; i < checkInLogs.size(); i++) {
            LocalDateTime currentCheckIn = LocalDateTime.parse(checkInLogs.get(i).getActivityTime(), formatter);
            
            // 计算当前签到与上次签到的天数差
            LocalDateTime expectedPrevDay = lastCheckIn.minusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            
            // 如果不是前一天签到，则连续性中断
            if (!currentCheckIn.toLocalDate().equals(expectedPrevDay.toLocalDate())) {
                break;
            }
            
            consecutiveDays++;
            lastCheckIn = currentCheckIn;
        }
        
        return consecutiveDays;
    }


    /**
     * 更新用户全局成就
     * @param chatId 群组ID
     * @param userId 用户ID
     * @param activityType 活动类型
     */
    private void updateGlobalAchievements(String chatId, String userId, String activityType, String userName) {
        try {
            // 获取全局成就列表
            List<Map<String, Object>> achievements = scoreDao.getGlobalAchievements();
            if (achievements.isEmpty()) {
                LoggingUtils.logOperation("NO_GLOBAL_ACHIEVEMENTS", userId, "系统没有设置全局成就");
                return;
            }
            
            // 根据活动类型更新不同的成就
            // 签到相关成就
            if ("check_in".equals(activityType)) {
                updateGlobalCheckInAchievements(chatId, userId, userName);
            }
            // 消息相关成就
            else if (activityType.endsWith("message")) {
                updateGlobalMessageCountAchievement(chatId, userId, userName);
            }
            // 互动相关成就
            else if (activityType.equals("message_reply") || activityType.equals("message_reaction")) {
                updateGlobalInteractionAchievements(chatId, userId, activityType, userName);
            }
            
            // 无论什么活动类型，都检查加入群聊时长成就
            updateGlobalJoinGroupDaysAchievement(chatId, userId, userName);
            
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_GLOBAL_ACHIEVEMENT_ERROR", "更新用户全局成就失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新全局签到相关成就
     * @param chatId 群组ID
     * @param userId 用户ID
     */
    private void updateGlobalCheckInAchievements(String chatId, String userId, String userName) {
        try {
            // 1. 更新累计签到天数成就
            updateGlobalTotalCheckInAchievement(chatId, userId, userName);
            
            // 2. 更新连续签到天数成就
            updateGlobalConsecutiveCheckInAchievement(chatId, userId, userName);
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_GLOBAL_CHECK_IN_ACHIEVEMENT_ERROR", "更新全局签到成就失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新全局累计签到天数成就
     * @param chatId 群组ID
     * @param userId 用户ID
     */
    private void updateGlobalTotalCheckInAchievement(String chatId, String userId, String userName) {
        try {
            // 获取用户累计签到次数
            int checkInCount = scoreDao.getUserGlobalCheckInCount(chatId, userId);
            // 更新成就进度
            scoreDao.updateUserGlobalAchievement(chatId, userId, userName, "total check-in", checkInCount);
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_GLOBAL_TOTAL_CHECK_IN_ERROR", "更新全局累计签到成就失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新全局连续签到天数成就
     * @param chatId 群组ID
     * @param userId 用户ID
     */
    private void updateGlobalConsecutiveCheckInAchievement(String chatId, String userId, String userName) {
        try {
            // 获取用户签到记录，按时间排序
            List<UserActivityLog> checkInLogs = jdbcTemplate.query(
                "SELECT * FROM user_activity_logs WHERE user_id = ? AND activity_type = 'check_in' ORDER BY activity_time DESC",
                (rs, rowNum) -> {
                    UserActivityLog log = new UserActivityLog();
                    log.setLogId(rs.getInt("log_id"));
                    log.setUserId(rs.getString("user_id"));
                    log.setActivityType(rs.getString("activity_type"));
                    log.setActivityTime(rs.getString("activity_time"));
                    log.setActivityLog(rs.getString("activity_log"));
                    log.setEventId(rs.getInt("event_id"));
                    log.setTopicId(rs.getInt("topic_id"));
                    return log;
                }, userId);
                
            if (checkInLogs.isEmpty()) {
                return;
            }
            
            // 计算当前连续签到天数
            int consecutiveDays = calculateConsecutiveCheckInDays(checkInLogs);
            
            // 更新连续签到成就
            scoreDao.updateUserGlobalAchievement(chatId, userId, userName, "Consecutive check-in", consecutiveDays);
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_GLOBAL_CONSECUTIVE_CHECK_IN_ERROR", "更新全局连续签到成就失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新全局消息发送条数成就
     * @param chatId 群组ID
     * @param userId 用户ID
     */
    private void updateGlobalMessageCountAchievement(String chatId, String userId, String userName) {
        try {
            // 获取用户消息发送次数
            int messageCount = scoreDao.getUserGlobalMessageCount(chatId, userId);
            
            // 更新消息发送成就
            scoreDao.updateUserGlobalAchievement(chatId, userId, userName, "Message Expert", messageCount);
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_GLOBAL_MESSAGE_COUNT_ERROR", "更新全局消息发送成就失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新全局互动相关成就
     * @param chatId 群组ID
     * @param userId 用户ID
     * @param interactionType 互动类型：message_reply 或 message_reaction
     */
    private void updateGlobalInteractionAchievements(String chatId, String userId, String interactionType, String userName) {
        try {
            // 获取用户被回复次数
            int replyCount = scoreDao.getUserGlobalInteractionCount(chatId, userId, "message_reply");
            
            // 获取用户被表情回复次数
            int reactionCount = scoreDao.getUserGlobalInteractionCount(chatId, userId, "message_reaction");
            
            // 总互动次数
            int totalInteractions = replyCount + reactionCount;
            
            scoreDao.updateUserGlobalAchievement(chatId, userId, userName, "Interaction Expert", totalInteractions);
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_GLOBAL_INTERACTION_ACHIEVEMENT_ERROR", "更新全局互动成就失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新全局加入群聊时长成就
     * @param chatId 群组ID
     * @param userId 用户ID
     */
    private void updateGlobalJoinGroupDaysAchievement(String chatId, String userId, String userName) {
        try {
            // 计算用户在所有群组中的最长加入天数
            String sql = "SELECT MAX(julianday('now') - julianday(join_time)) AS max_days FROM user_join_time WHERE user_id = ?";
            List<Double> results = jdbcTemplate.queryForList(sql, Double.class, userId);
            
            if (results.isEmpty() || results.get(0) == null) {
                return;
            }
            
            int maxJoinDays = (int) Math.floor(results.get(0));
            
            scoreDao.updateUserGlobalAchievement(chatId, userId, userName, "Community leaders", maxJoinDays);
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_GLOBAL_JOIN_ACHIEVEMENT_ERROR", "更新全局加入群聊时长成就失败: " + e.getMessage(), e);
        }
    }
    
    // Getter and Setter for Spring property binding
    public Map<String, Integer> getPoints() {
        return points;
    }

    public void setPoints(Map<String, Integer> points) {
        this.points = points;
    }

    public Map<String, Object> getCooldown() {
        return cooldown;
    }

    public void setCooldown(Map<String, Object> cooldown) {
        this.cooldown = cooldown;
    }
    
    public Map<String, String> getCheck_in() {
        return check_in;
    }
    
    public void setCheck_in(Map<String, String> check_in) {
        this.check_in = check_in;
    }
    
    public Map<String, Object> getInteraction() {
        return interaction;
    }
    
    public void setInteraction(Map<String, Object> interaction) {
        this.interaction = interaction;
    }

    /**
     * 添加活动
     * @param ctx
     */
    public void addEvent(MessageContext ctx) {
        ConstructionEventContext.chatId = String.valueOf(ctx.chatId());
       ConstructionEventContext.creator_id = ctx.update().getMessage().getFrom().getId().toString();
       ConstructionEventContext.constructionEvent = new EventRecord();
       ConstructionEventContext.constructionEvent.setCreatorId(ctx.update().getMessage().getFrom().getId().toString());
       // 构建消息：用户{}开始创建活动，请按照指引发送响应信息，在创建过程中请不要在任何群聊发送任何其他信息，终止活动创建请发送`cancel!!!`，请您发送活动名称
       SendMessage message = SendMessage.builder()
           .chatId(ctx.chatId())
           .text("用户" + ctx.update().getMessage().getFrom().getUserName() + "开始创建活动，请按照指引发送响应信息，在创建过程中请不要在任何群聊发送任何其他信息，终止活动创建请发送`cancel!!!`，请您发送活动名称")
           .build();
       Object bot = applicationContext.getBean("myAmazingBot");
       try {
           bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
       } catch (Exception e) {
           LoggingUtils.logError("ADD_EVENT_ERROR", "添加活动失败", e);
       }
    }
    /**
     * 添加活动
     * @param message 用户消息
     */
    public void addEvent(String message){
        if(message.equals("cancel!!!")){
            SendMessage toMessage = SendMessage.builder()
                .chatId(ConstructionEventContext.chatId)
                .text("活动创建已取消")
                .build();
            ConstructionEventContext.chatId = null;
            ConstructionEventContext.creator_id = null;
            ConstructionEventContext.constructionEvent = null;
            ConstructionEventContext.eventList = null;
            Object bot = applicationContext.getBean("myAmazingBot");
            try {
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
            } catch (Exception e) {
                addEventError(e);
            }
        }else if(ConstructionEventContext.constructionEvent.getEventName() == null){
            try {
                ConstructionEventContext.constructionEvent.setEventName(message);
                SendMessage toMessage = SendMessage.builder()
                    .chatId(ConstructionEventContext.chatId)
                    .text("请发送活动描述")
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
            } catch (Exception e) {
                addEventError(e);
            }
        }else if(ConstructionEventContext.constructionEvent.getEventDescription() == null){
            try {
                ConstructionEventContext.constructionEvent.setEventDescription(message);
                SendMessage toMessage = SendMessage.builder()
                    .chatId(ConstructionEventContext.chatId)
                    .text("请发送活动开始时间,时间格式请严格按照`2006-03-26 15:04:05`格式")
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
            } catch (Exception e) {
                addEventError(e);
            }
        }else if(ConstructionEventContext.constructionEvent.getStartTime() == null){
            try {
                // 使用TimeFormatUtil格式化和验证时间
                String formattedTime = TimeFormatUtil.validateAndFormat(message);
                
                ConstructionEventContext.constructionEvent.setStartTime(formattedTime);
                SendMessage toMessage = SendMessage.builder()
                    .chatId(ConstructionEventContext.chatId)
                    .text("请发送活动结束时间,时间格式请严格按照`2006-03-26 15:04:05`格式")
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
            } catch (java.text.ParseException e) {
                SendMessage toMessage = SendMessage.builder()
                    .chatId(ConstructionEventContext.chatId)
                    .text("时间格式错误，请重新输入，格式为`2006-03-26 15:04:05`")
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                try {
                    bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
                } catch (Exception ex) {
                    addEventError(ex);
                }
                return;
            } catch (Exception e) {
                addEventError(e);
            }
        }else if(ConstructionEventContext.constructionEvent.getEndTime() == null){
            try {
                // 使用TimeFormatUtil格式化和验证时间
                String formattedTime = TimeFormatUtil.validateAndFormat(message);
                
                // 判断结束时间是否晚于开始时间
                String startTimeStr = ConstructionEventContext.constructionEvent.getStartTime();
                if (!TimeFormatUtil.isEndTimeAfterStartTime(startTimeStr, formattedTime)) {
                    ConstructionEventContext.constructionEvent.setStartTime(null);
                    ConstructionEventContext.constructionEvent.setEndTime(null);
                    SendMessage toMessage = SendMessage.builder()
                        .chatId(ConstructionEventContext.chatId)
                        .text("结束时间不能早于开始时间，请重新输入活动开始时间，时间格式请严格按照`2006-03-26 15:04:05`格式")
                        .build();
                    Object bot = applicationContext.getBean("myAmazingBot");
                    bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
                    return;
                }
                
                ConstructionEventContext.constructionEvent.setEndTime(formattedTime);
                // 查询所有活动群聊
                List<Map<String, String>> groupList = scoreDao.getAllGroupChats().stream()
                    .map(map -> map.entrySet().stream()
                        .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().toString()
                        ))
                    ).collect(Collectors.toList());
                ConstructionEventContext.eventList = groupList;
                StringBuilder sb = new StringBuilder("请根据序号选择活动群聊：\n");
                for (int i = 0; i < groupList.size(); i++) {
                    Map<String, String> group = groupList.get(i);
                    sb.append(i + 1).append(". ").append(group.get("group_name")).append("\n");
                }
                SendMessage toMessage = SendMessage.builder()
                    .chatId(ConstructionEventContext.chatId)
                    .text(sb.toString())
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
            } catch (java.text.ParseException e) {
                SendMessage toMessage = SendMessage.builder()
                    .chatId(ConstructionEventContext.chatId)
                    .text("时间格式错误，请重新输入，格式为`2006-03-26 15:04:05`")
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                try {
                    bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
                } catch (Exception ex) {
                    addEventError(ex);
                }
                return;
            } catch (Exception e) {
                addEventError(e);
            }
        }else if(ConstructionEventContext.constructionEvent.getEventGroupId() == null){
            try {
                int idx = Integer.parseInt(message.trim()) - 1;
                if (ConstructionEventContext.eventList != null && idx >= 0 && idx < ConstructionEventContext.eventList.size()) {
                    Map<String, String> group = (Map<String, String>) ConstructionEventContext.eventList.get(idx);
                    ConstructionEventContext.constructionEvent.setEventGroupId(group.get("group_id"));
                } else {
                    SendMessage toMessage = SendMessage.builder()
                        .chatId(ConstructionEventContext.chatId)
                        .text("无效的序号，请重新选择")
                        .build();
                    Object bot = applicationContext.getBean("myAmazingBot");
                    bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
                    return;
                }
                // 查询所有活动群聊，构建管理员群聊选择消息
                List<Map<String, String>> groupList = ConstructionEventContext.eventList;
                StringBuilder sb = new StringBuilder("请根据序号选择管理员群聊：\n");
                for (int i = 0; i < groupList.size(); i++) {
                    Map<String, String> group = groupList.get(i);
                    sb.append(i + 1).append(". ").append(group.get("group_name")).append("\n");
                }
                ConstructionEventContext.eventList = groupList;
                SendMessage toMessage = SendMessage.builder()
                    .chatId(ConstructionEventContext.chatId)
                    .text(sb.toString())
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
            } catch (Exception e) {
                addEventError(e);
            }
        }else if(ConstructionEventContext.constructionEvent.getAdminGroupId() == null){
            try {
                int idx = Integer.parseInt(message.trim()) - 1;
                if (ConstructionEventContext.eventList != null && idx >= 0 && idx < ConstructionEventContext.eventList.size()) {
                    Map<String, String> group = (Map<String, String>) ConstructionEventContext.eventList.get(idx);
                    ConstructionEventContext.constructionEvent.setAdminGroupId(group.get("group_id"));
                } else {
                    SendMessage toMessage = SendMessage.builder()
                        .chatId(ConstructionEventContext.chatId)
                        .text("无效的序号，请重新选择")
                        .build();
                    Object bot = applicationContext.getBean("myAmazingBot");
                    bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
                    return;
                }
            String eventName = ConstructionEventContext.constructionEvent.getEventName();
            String eventDescription = ConstructionEventContext.constructionEvent.getEventDescription();
            String eventGroupId = ConstructionEventContext.constructionEvent.getEventGroupId();
            String adminGroupId = ConstructionEventContext.constructionEvent.getAdminGroupId();
            String startTime = ConstructionEventContext.constructionEvent.getStartTime();
            String endTime = ConstructionEventContext.constructionEvent.getEndTime();
            String messageText = "请输入yes确认创建活动或输入false取消此次活动创建，活动详情：\n" +
                                 "eventName: " + eventName + "\n" +
                                 "eventDescription: " + eventDescription + "\n" +
                                 "eventGroupId: " + eventGroupId + "\n" +
                                 "adminGroupId: " + adminGroupId + "\n" +
                                 "startTime: " + startTime + "\n" +
                                 "endTime: " + endTime + "\n……";
            SendMessage toMessage = SendMessage.builder()
                .chatId(ConstructionEventContext.chatId)
                .text(messageText)
                .build();
            Object bot = applicationContext.getBean("myAmazingBot");
            bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
            } catch (Exception e) {
                addEventError(e);
            }
        }else if(message.equals("yes")){
            try {
                boolean created = scoreDao.createEvent(ConstructionEventContext.constructionEvent);
                String reply = created ? "活动创建成功！" : "活动创建失败，请重新使用`/addEvent`命令。";
                SendMessage toMessage = SendMessage.builder()
                    .chatId(ConstructionEventContext.chatId)
                    .text(reply)
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
                // 清空上下文
                ConstructionEventContext.chatId = null;
                ConstructionEventContext.creator_id = null;
                ConstructionEventContext.constructionEvent = null;
                ConstructionEventContext.eventList = null;
            } catch (Exception e) {
                addEventError(e);
            }
        }else if(message.equals("false")){
            try {
                SendMessage toMessage = SendMessage.builder()
                .chatId(ConstructionEventContext.chatId)
                .text("活动创建已取消")
                .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, toMessage);
                ConstructionEventContext.chatId = null;
                ConstructionEventContext.creator_id = null;
                ConstructionEventContext.constructionEvent = null;
                ConstructionEventContext.eventList = null;
            } catch (Exception e) {
                addEventError(e);
            }
        }else{
            addEventError(new Exception("无效的输入"));
        }
    }

    /**
     * 添加活动失败
     * @param e
     */
    public void addEventError(Exception e){
        LoggingUtils.logError("ADD_EVENT_ERROR", "添加活动失败，请重新使用`/addEvent`命令", e);
        SendMessage message = SendMessage.builder()
            .chatId(ConstructionEventContext.chatId)
            .text("添加活动失败，请重新使用`/addEvent`命令")
            .build();
        Object bot = applicationContext.getBean("myAmazingBot");
        ConstructionEventContext.chatId = null;
        ConstructionEventContext.creator_id = null;
        ConstructionEventContext.constructionEvent = null;
        try {
            bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
        } catch (Exception a) {
            LoggingUtils.logError("ADD_EVENT_ERROR", "添加活动失败，请重新使用`/addEvent`命令", a);
        }
        
    }

    public void initChat(MessageContext ctx) {
        // 获取群聊ID和名称
        String groupId = String.valueOf(ctx.chatId());
        String groupName = null;
        Integer topicId = null;
        String topicName = null;
        try {
            // 获取群聊名称
            if (ctx.update() != null && ctx.update().getMessage() != null && ctx.update().getMessage().getChat() != null) {
                groupName = ctx.update().getMessage().getChat().getTitle();
            }
            // 判断是否为话题消息
            if (ctx.update() != null && ctx.update().getMessage() != null && ctx.update().getMessage().isTopicMessage()) {
                topicId = ctx.update().getMessage().getMessageThreadId();
                // 话题名称优先取forumTopicCreated
                if (ctx.update().getMessage().getReplyToMessage().getForumTopicCreated() != null) {
                    topicName = ctx.update().getMessage().getReplyToMessage().getForumTopicCreated().getName();
                } else {
                    // 兼容性处理：如无forumTopicCreated则用chat title
                    topicName = groupName;
                }
            }
        } catch (Exception e) {
            // 容错处理
        }
        // 判断是否已存在，存在则更新，否则插入
        if (scoreDao.existsGroupChatRecord(groupId, topicId)) {
            scoreDao.updateGroupChatRecord(groupId, groupName, topicId, topicName);
        } else {
            scoreDao.insertGroupChatRecord(groupId, groupName, topicId, topicName);
        }
    }

    public void initAdminGroup(MessageContext ctx) {
        String adminGroupId = ctx.update().getMessage().getChat().getId().toString();
        scoreDao.insertAdminGroup(adminGroupId);
    }

    /**
     * 为用户添加特殊积分
     * @param chatId 群聊ID
     * @param userName 用户名（去除@符号）
     * @param specialPoints 要添加的特殊积分
     * @return 操作结果消息
     */
    public String addPointsToUser(String chatId, String userName, int specialPoints) {
        try {
            // 如果用户名以@开头，去除@符号
            if (userName.startsWith("@")) {
                userName = userName.substring(1);
            }
            
            // 通过用户名查找用户ID
            String userId = scoreDao.getUserIdByUserName(userName);
            if (userId == null) {
                return "错误：未找到用户 @" + userName + "，请确保该用户已在系统中有记录。";
            }
            
            // 获取该群聊的所有活跃活动
            List<EventRecord> activeEvents = scoreDao.getActiveEventsByGroupId(chatId);
            if (activeEvents.isEmpty()) {
                return "错误：当前群聊没有活跃的活动。";
            }
            
            int successCount = 0;
            int totalEvents = activeEvents.size();
            for (EventRecord event : activeEvents) {
                boolean success = scoreDao.updateUserPoints(event.getEventId(), "群聊", userId, userName, 0, specialPoints);
                if (success) {
                    successCount++;
                }
            }
            
            if (successCount > 0) {
                String action = specialPoints >= 0 ? "添加了" : "扣减了";
                LoggingUtils.logOperation("ADD_SPECIAL_POINTS", userId, 
                    String.format("管理员为用户 %s %s %d 特殊积分", userName, action, Math.abs(specialPoints)));
                
                String actionText = specialPoints >= 0 ? "添加了" : "扣减了";
                return String.format("成功为用户 @%s 在 %d/%d 个活动中%s %d 特殊积分。%s", 
                    userName, successCount, totalEvents, actionText, Math.abs(specialPoints),
                    (successCount < totalEvents) ? "（部分活动中该用户无积分记录，已跳过）" : "");
            } else {
                return "操作失败：用户在所有活动中都没有积分记录，无法添加特殊积分。";
            }
            
        } catch (Exception e) {
            LoggingUtils.logError("ADD_POINTS_TO_USER_ERROR", "为用户添加积分失败: " + e.getMessage(), e);
            return "操作失败：" + e.getMessage();
        }
    }

    public void viewPoints(MessageContext ctx) {
        String userId = String.valueOf(ctx.update().getMessage().getFrom().getId());
        String chatId = String.valueOf(ctx.chatId());
        String userName = ctx.update().getMessage().getFrom().getUserName();
        
        try {
            // 获取当前群聊所有正在进行的活动
            List<EventRecord> activeEvents = scoreDao.getActiveEventsByGroupId(chatId);
            
            if (activeEvents.isEmpty()) {
                SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text("当前群聊没有正在进行的活动。")
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
                return;
            }
            
            // 获取用户已完成的成就列表
            List<Map<String, Object>> userAchievements = scoreDao.getUserCompletedAchievements(userId, chatId);
            
            // 为每个活动发送一条消息
            for (EventRecord event : activeEvents) {
                int eventId = event.getEventId();
                double achievementBonus = scoreDao.getUserAchievementBonus(userId, eventId);
                int points = scoreDao.getUserPoints(userId, eventId);
                int specialPoints = scoreDao.getUserSpecialPoints(userId, eventId);
                double finalPoints = points * achievementBonus + specialPoints;
                
                StringBuilder messageBuilder = new StringBuilder();
                messageBuilder.append(String.format("用户%s在当前活动%s的总积分为%.2f\n", 
                    userName != null ? userName : "未知", event.getEventName(), finalPoints));
                
                // 添加成就列表
                if (!userAchievements.isEmpty()) {
                    messageBuilder.append("成就列表：\n");
                    for (Map<String, Object> achievement : userAchievements) {
                        String achievementName = (String) achievement.get("achievement_name");
                        Object eventIdObj = achievement.get("event_id");
                        String level = eventIdObj != null ? eventIdObj.toString() : "0";
                        messageBuilder.append(String.format("%s-等级：%s\n", achievementName, level));
                    }
                } else {
                    messageBuilder.append("成就列表：暂无已完成的成就\n");
                }
                
                SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(messageBuilder.toString())
                    .build();
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
            }
            
        } catch (Exception e) {
            LoggingUtils.logError("VIEW_POINTS_ERROR", "查看积分失败: " + e.getMessage(), e);
            SendMessage errorMessage = SendMessage.builder()
                .chatId(chatId)
                .text("查看积分失败，请稍后再试。")
                .build();
            try {
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, errorMessage);
            } catch (Exception ex) {
                LoggingUtils.logError("VIEW_POINTS_REPLY_ERROR", "发送错误消息失败: " + ex.getMessage(), ex);
            }
        }
    }

    /**
     * 获取活动列表消息
     * @param chatId 群聊ID
     * @return 活动列表消息
     */
    public String getEventListMessage(String chatId) {
        try {
            List<EventRecord> events = scoreDao.getAllEvents();
            if (events.isEmpty()) {
                return "当前没有任何活动。";
            }
            
            StringBuilder sb = new StringBuilder("请选择具体的活动id，例如`/pointList 1`，活动列表：\n");
            for (EventRecord event : events) {
                sb.append(event.getEventId()).append(" ").append(event.getEventName()).append("\n");
            }
            
            return sb.toString();
        } catch (Exception e) {
            LoggingUtils.logError("GET_EVENT_LIST_ERROR", "获取活动列表失败: " + e.getMessage(), e);
            return "获取活动列表失败，请稍后再试。";
        }
    }

    /**
     * 获取活动积分排名消息和键盘
     * @param eventId 活动ID
     * @param page 页码（从0开始）
     * @param chatId 群聊ID
     * @return 包含消息文本和键盘的Map
     */
    public Map<String, Object> getEventPointsRankingMessage(int eventId, int page, String chatId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取活动信息
            EventRecord event = scoreDao.getEventById(eventId);
            if (event == null) {
                result.put("text", "活动不存在，请检查活动ID。");
                result.put("keyboard", null);
                return result;
            }
            
            // 检查活动是否已开始
            if (!isEventStarted(event)) {
                result.put("text", "所选活动还没有开始");
                result.put("keyboard", null);
                return result;
            }
            
            // 获取用户总数和总页数
            int pageSize = 15;
            int totalUsers = scoreDao.getEventUserCount(eventId);
            int totalPages = (int) Math.ceil((double) totalUsers / pageSize);
            
            if (totalUsers == 0) {
                result.put("text", String.format("活动：%s 暂无用户积分记录。", event.getEventName()));
                result.put("keyboard", null);
                return result;
            }
            
            // 确保页码在有效范围内
            if (page < 0) page = 0;
            if (page >= totalPages) page = totalPages - 1;
            
            // 获取积分排名
            List<Map<String, Object>> rankings = scoreDao.getEventPointsRanking(eventId, page, pageSize);
            
            // 构建消息文本
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("活动：%s的积分排名如下\n", event.getEventName()));
            sb.append("排名   用户名   积分\n");
            
            for (int i = 0; i < rankings.size(); i++) {
                Map<String, Object> ranking = rankings.get(i);
                int rank = page * pageSize + i + 1;
                String userName = (String) ranking.get("user_name");
                if (userName == null || userName.trim().isEmpty()) {
                    userName = "未知用户";
                }
                Object finalPointsObj = ranking.get("final_points");
                String finalPoints = finalPointsObj != null ? String.format("%.2f", Double.parseDouble(finalPointsObj.toString())) : "0.00";
                
                sb.append(String.format("%d     %s      %s\n", rank, userName, finalPoints));
            }
            
            result.put("text", sb.toString());
            
            // 构建分页键盘
            if (totalPages > 1) {
                result.put("keyboard", createPaginationKeyboard(eventId, page, totalPages));
            } else {
                result.put("keyboard", null);
            }
            
            return result;
            
        } catch (Exception e) {
            LoggingUtils.logError("GET_EVENT_POINTS_RANKING_ERROR", "获取活动积分排名失败: " + e.getMessage(), e);
            result.put("text", "获取积分排名失败，请稍后再试。");
            result.put("keyboard", null);
            return result;
        }
    }

    /**
     * 检查活动是否已开始
     * @param event 活动记录
     * @return 是否已开始
     */
    private boolean isEventStarted(EventRecord event) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startTime = LocalDateTime.parse(event.getStartTime(), formatter);
            return now.isAfter(startTime) || now.isEqual(startTime);
        } catch (Exception e) {
            LoggingUtils.logError("CHECK_EVENT_STARTED_ERROR", "检查活动开始时间失败: " + e.getMessage(), e);
            return false; // 如果解析失败，默认认为未开始
        }
    }

    /**
     * 创建分页键盘
     * @param eventId 活动ID
     * @param currentPage 当前页码
     * @param totalPages 总页数
     * @return 内联键盘
     */
    private Object createPaginationKeyboard(int eventId, int currentPage, int totalPages) {
        try {
            // 使用反射创建InlineKeyboardMarkup和相关对象
            Class<?> inlineKeyboardMarkupClass = Class.forName("org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup");
            Class<?> inlineKeyboardButtonClass = Class.forName("org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton");
            Class<?> inlineKeyboardRowClass = Class.forName("org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow");
            
            Object keyboardMarkup = inlineKeyboardMarkupClass.getDeclaredConstructor().newInstance();
            Object keyboardRow = inlineKeyboardRowClass.getDeclaredConstructor().newInstance();
            
            // 创建按钮列表
            List<Object> buttons = new ArrayList<>();
            
            // 上一页按钮
            if (currentPage > 0) {
                Object prevButton = inlineKeyboardButtonClass.getDeclaredConstructor().newInstance();
                inlineKeyboardButtonClass.getMethod("setText", String.class).invoke(prevButton, "⬅️上一页");
                inlineKeyboardButtonClass.getMethod("setCallbackData", String.class).invoke(prevButton, 
                    String.format("pointList_%d_%d", eventId, currentPage - 1));
                buttons.add(prevButton);
            }
            
            // 页码信息按钮
            Object pageInfoButton = inlineKeyboardButtonClass.getDeclaredConstructor().newInstance();
            inlineKeyboardButtonClass.getMethod("setText", String.class).invoke(pageInfoButton, 
                String.format("%d/%d", currentPage + 1, totalPages));
            inlineKeyboardButtonClass.getMethod("setCallbackData", String.class).invoke(pageInfoButton, "page_info");
            buttons.add(pageInfoButton);
            
            // 下一页按钮
            if (currentPage < totalPages - 1) {
                Object nextButton = inlineKeyboardButtonClass.getDeclaredConstructor().newInstance();
                inlineKeyboardButtonClass.getMethod("setText", String.class).invoke(nextButton, "下一页➡️");
                inlineKeyboardButtonClass.getMethod("setCallbackData", String.class).invoke(nextButton, 
                    String.format("pointList_%d_%d", eventId, currentPage + 1));
                buttons.add(nextButton);
            }
            
            // 设置按钮到行
            inlineKeyboardRowClass.getMethod("addAll", java.util.Collection.class).invoke(keyboardRow, buttons);
            
            // 设置行到键盘
            List<Object> rows = List.of(keyboardRow);
            inlineKeyboardMarkupClass.getMethod("setKeyboard", List.class).invoke(keyboardMarkup, rows);
            
            return keyboardMarkup;
            
        } catch (Exception e) {
            LoggingUtils.logError("CREATE_PAGINATION_KEYBOARD_ERROR", "创建分页键盘失败: " + e.getMessage(), e);
            return null;
        }
    }
}
