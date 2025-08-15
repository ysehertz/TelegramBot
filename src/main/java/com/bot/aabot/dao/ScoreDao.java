package com.bot.aabot.dao;

import com.bot.aabot.entity.EventAchievement;
import com.bot.aabot.entity.EventRecord;
import com.bot.aabot.entity.UserAchievement;
import com.bot.aabot.entity.UserActivityLog;
import com.bot.aabot.utils.LoggingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ClassName: ScoreDao
 * Package: com.bot.aabot.dao
 * Description: 处理积分和成就相关的数据库操作
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/27
 */
@Component
public class ScoreDao {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 根据群组ID查询相关的活动ID列表
     *
     * @param groupId 群组ID
     * @return 活动ID列表
     */
    public List<EventRecord> getActiveEventsByGroupId(String groupId) {
        String sql = "SELECT * FROM event_records WHERE event_group_id = ? AND " +
                    "datetime('now') BETWEEN datetime(start_time) AND datetime(end_time)";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                EventRecord event = new EventRecord();
                event.setEventId(rs.getInt("event_id"));
                event.setEventName(rs.getString("event_name"));
                event.setEventDescription(rs.getString("event_description"));
                event.setStartTime(rs.getString("start_time"));
                event.setEndTime(rs.getString("end_time"));
                event.setEventGroupId(rs.getString("event_group_id"));
                event.setAdminGroupId(rs.getString("admin_group_id"));
                return event;
            }, groupId);
        } catch (Exception e) {
            LoggingUtils.logError("GET_EVENTS_ERROR", "获取活动列表失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取用户在特定活动中的最近活动日志
     *
     * @param eventId 活动ID
     * @param userId 用户ID
     * @param activityType 活动类型
     * @return 用户活动日志
     */
    public List<UserActivityLog> getUserRecentActivityLogs(int eventId, String userId, String activityType) {
        String sql = "SELECT * FROM user_activity_logs WHERE event_id = ? AND user_id = ? AND activity_type = ? " +
                    "ORDER BY activity_time DESC LIMIT 10";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                UserActivityLog log = new UserActivityLog();
                log.setLogId(rs.getInt("log_id"));
                log.setUserId(rs.getString("user_id"));
                log.setActivityType(rs.getString("activity_type"));
                log.setActivityTime(rs.getString("activity_time"));
                log.setActivityLog(rs.getString("activity_log"));
                log.setEventId(rs.getInt("event_id"));
                log.setTopicId(rs.getInt("topic_id"));
                return log;
            }, eventId, userId, activityType);
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_LOGS_ERROR", "获取用户活动日志失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 添加用户活动日志
     *
     * @param log 用户活动日志
     * @return 是否添加成功
     */
    public boolean addUserActivityLog(UserActivityLog log) {
        String sql = "INSERT INTO user_activity_logs (user_id, activity_type, activity_time, activity_log, event_id, topic_id,chat_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?,?)";
        try {
            LocalDateTime now = LocalDateTime.now();
            int rows = jdbcTemplate.update(sql, 
                log.getUserId(),
                log.getActivityType(),
                now.format(formatter),
                log.getActivityLog(),
                log.getEventId(),
                log.getTopicId(),
                    log.getChatId());
            return rows > 0;
        } catch (Exception e) {
            LoggingUtils.logError("ADD_USER_LOG_ERROR", "添加用户活动日志失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取用户在特定活动中的积分
     *
     * @param eventId 活动ID
     * @param userId 用户ID
     * @return 用户积分信息
     */
    public Map<String, Object> getUserPoints(int eventId, String userId) {
        String sql = "SELECT * FROM user_points WHERE event_id = ? AND user_id = ?";
        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, eventId, userId);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_POINTS_ERROR", "获取用户积分失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 更新用户积分
     *
     * @param eventId 活动ID
     * @param userId 用户ID
     * @param pointsToAdd 要增加的积分
     * @param specialPointsToAdd 要增加的特殊积分
     * @return 是否更新成功
     */
    public boolean updateUserPoints(int eventId,String chatName, String userId, String userName, int pointsToAdd, int specialPointsToAdd) {
        try {
            Map<String, Object> userPoints = getUserPoints(eventId, userId);
            if (userPoints == null) {
                // 用户在该活动中没有积分记录，创建新记录
                String insertSql = "INSERT INTO user_points (event_id, user_id, user_name, points, special_points, role, chat_name) " +
                                 "VALUES (?, ?, ?, ?, ?, 'member', ?)";
                int rows = jdbcTemplate.update(insertSql, eventId, userId, userName, pointsToAdd, specialPointsToAdd, chatName);
                return rows > 0;
            } else {
                // 用户已有积分记录，更新积分和用户名
                int currentPoints = (int) userPoints.get("points");
                int currentSpecialPoints = (int) userPoints.get("special_points");
                String updateSql = "UPDATE user_points SET points = ?, special_points = ?, user_name = ? " +
                                 "WHERE event_id = ? AND user_id = ? ";
                int rows = jdbcTemplate.update(updateSql, 
                    currentPoints + pointsToAdd, 
                    currentSpecialPoints + specialPointsToAdd, 
                    userName,
                    eventId, 
                    userId
                );
                return rows > 0;
            }
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_USER_POINTS_ERROR", "更新用户积分失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取活动的成就列表
     *
     * @param eventId 活动ID
     * @return 成就列表
     */
    public List<EventAchievement> getEventAchievements(int eventId) {
        String sql = "SELECT * FROM event_achievements WHERE event_id = ?";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                EventAchievement achievement = new EventAchievement();
                achievement.setEventId(rs.getInt("event_id"));
                achievement.setAchievementName(rs.getString("achievement_name"));
                achievement.setAchievementId(rs.getString("achievement_id"));
                achievement.setAchievementDescription(rs.getString("achievement_description"));
                achievement.setAchievementType(rs.getString("achievement_type"));
                achievement.setConditionCount(rs.getInt("condition_count"));
                achievement.setReward(rs.getString("reward"));
                return achievement;
            }, eventId);
        } catch (Exception e) {
            LoggingUtils.logError("GET_EVENT_ACHIEVEMENTS_ERROR", "获取活动成就失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取用户的成就
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 用户ID
     * @param achievementId 成就ID
     * @return 用户成就
     */
    public UserAchievement getUserAchievement(String chatId, int eventId, String userId, String achievementId) {
        String sql = "SELECT * FROM user_achievements WHERE chat_id = ? AND event_id = ? AND user_id = ? AND achievement_name = ?";
        try {
            List<UserAchievement> achievements = jdbcTemplate.query(sql, (rs, rowNum) -> {
                UserAchievement achievement = new UserAchievement();
                achievement.setAchievementId(rs.getInt("achievement_id"));
                achievement.setUserId(rs.getString("user_id"));
                achievement.setAchievementName(rs.getString("achievement_name"));
                achievement.setProgress(rs.getInt("progress"));
                achievement.setCompleteTime(rs.getString("complete_time"));
                achievement.setEventId(rs.getInt("event_id"));
                achievement.setChatId(rs.getString("chat_id"));
                return achievement;
            }, chatId, eventId, userId, achievementId);
            return achievements.isEmpty() ? null : achievements.get(0);
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_ACHIEVEMENT_ERROR", "获取用户成就失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 更新用户成就进度
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 用户ID
     * @param achievementId 成就ID
     * @param achievementName 成就名称
     * @param progressToAdd 要增加的进度
     * @return 是否更新成功
     */
    public boolean updateUserAchievement(String chatId, int eventId, String userId, String achievementId, String achievementName, int progressToAdd) {
        try {
            UserAchievement userAchievement = getUserAchievement(chatId, eventId, userId, achievementId);
            LocalDateTime now = LocalDateTime.now();
            
            if (userAchievement == null) {
                // 用户没有该成就记录，创建新记录
                String insertSql = "INSERT INTO user_achievements (chat_id, event_id, user_id, achievement_name, progress) " +
                                 "VALUES (?, ?, ?, ?, ?)";
                int rows = jdbcTemplate.update(insertSql, chatId, eventId, userId, achievementName, progressToAdd);
                return rows > 0;
            } else if (userAchievement.getCompleteTime() == null) {
                // 用户有成就记录但未完成，更新进度
                int currentProgress = userAchievement.getProgress();
                int newProgress = currentProgress + progressToAdd;
                
                // 获取成就完成条件
                List<EventAchievement> achievements = getEventAchievements(eventId);
                // 获取成就完成条件
                EventAchievement achievement = achievements.stream()
                    .filter(a -> a.getAchievementId().equals(achievementId))
                    .findFirst()
                    .orElse(null);
                
                if (achievement != null && newProgress >= achievement.getConditionCount()) {
                    // 成就已完成，记录完成时间
                    String updateSql = "UPDATE user_achievements SET progress = ?, complete_time = ? " +
                                     "WHERE chat_id = ? AND event_id = ? AND user_id = ? AND achievement_name = ?";
                    int rows = jdbcTemplate.update(updateSql, 
                        newProgress, 
                        now.format(formatter), 
                        chatId, 
                        eventId, 
                        userId, 
                        achievementName);
                    return rows > 0;
                } else {
                    // 成就未完成，仅更新进度
                    String updateSql = "UPDATE user_achievements SET progress = ? " +
                                     "WHERE chat_id = ? AND event_id = ? AND user_id = ? AND achievement_name = ?";
                    int rows = jdbcTemplate.update(updateSql, 
                        newProgress, 
                        chatId, 
                        eventId, 
                        userId, 
                        achievementName);
                    return rows > 0;
                }
            } else {
                // 用户已完成该成就，不做更新
                return true;
            }
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_USER_ACHIEVEMENT_ERROR", "更新用户成就失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查用户今日是否已签到
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 用户ID
     * @return 是否已签到
     */
    public boolean hasUserCheckedInToday(String chatId, int eventId, String userId) {
        String today = LocalDateTime.now().format(dateFormatter);
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND event_id = ? AND user_id = ? AND activity_type = 'check_in' AND substr(activity_time, 1, 10) = ?";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, eventId, userId, today);
            return count != null && count > 0;
        } catch (Exception e) {
            LoggingUtils.logError("CHECK_IN_QUERY_ERROR", "查询用户签到状态失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 记录用户签到
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 用户ID
     * @param message 签到消息内容
     * @return 是否成功记录
     */
    public boolean recordUserCheckIn(String chatId, int eventId, String userId, String message) {
        try {
            // 创建用户活动日志记录
            UserActivityLog log = new UserActivityLog();
            log.setChatId(chatId);
            log.setEventId(eventId);
            log.setUserId(userId);
            log.setActivityType("check_in");
            log.setActivityLog(message);
            
            // 添加签到记录
            return addUserActivityLog(log);
        } catch (Exception e) {
            LoggingUtils.logError("RECORD_CHECK_IN_ERROR", "记录用户签到失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取消息已被互动的次数
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param messageId 消息ID
     * @return 已被互动次数
     */
    public int getMessageInteractionCount(String chatId, int eventId, Integer messageId) {
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND event_id = ? " +
                    "AND activity_type IN ('message_reply', 'message_reaction') " +
                    "AND activity_log LIKE ?";
        try {
            String logPattern = "%" + messageId + "%";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, eventId, logPattern);
            return count != null ? count : 0;
        } catch (Exception e) {
            LoggingUtils.logError("GET_INTERACTION_COUNT_ERROR", "获取消息互动次数失败: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 检查特定用户是否已对特定消息进行过互动
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 用户ID
     * @param messageId 消息ID
     * @param interactionType 互动类型 (message_reply 或 message_reaction)
     * @return 是否已互动
     */
    public boolean hasUserInteractedWithMessage(String chatId, int eventId, String userId, Integer messageId, String interactionType) {
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND event_id = ? AND user_id = ? AND activity_type = ? AND activity_log LIKE ?";
        try {
            String logPattern = "%" + messageId + "%";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, eventId, userId, interactionType, logPattern);
            return count != null && count > 0;
        } catch (Exception e) {
            LoggingUtils.logError("CHECK_USER_INTERACTION_ERROR", "检查用户消息互动失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 记录消息互动
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 互动者用户ID
     * @param targetUserId 被互动者用户ID
     * @param messageId 消息ID
     * @param interactionType 互动类型 (message_reply 或 message_reaction)
     * @return 是否成功记录
     */
    public boolean recordMessageInteraction(String chatId, int eventId, String userId, String targetUserId, Integer messageId, String interactionType) {
        try {
            // 创建活动日志记录
            UserActivityLog log = new UserActivityLog();
            log.setChatId(chatId);
            log.setEventId(eventId);
            log.setUserId(userId);
            log.setActivityType(interactionType);
            log.setActivityLog("互动消息ID:" + messageId + ",目标用户:" + targetUserId);
            
            // 添加互动记录
            return addUserActivityLog(log);
        } catch (Exception e) {
            LoggingUtils.logError("RECORD_INTERACTION_ERROR", "记录消息互动失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取用户在特定活动中的累计签到次数
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 用户ID
     * @return 累计签到次数
     */
    public int getUserCheckInCount(String chatId, int eventId, String userId) {
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND event_id = ? AND user_id = ? AND activity_type = 'check_in'";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, eventId, userId);
            return count != null ? count : 0;
        } catch (Exception e) {
            LoggingUtils.logError("GET_CHECK_IN_COUNT_ERROR", "获取用户签到次数失败: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 获取用户在特定活动中的特定类型活动日志
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 用户ID
     * @param activityType 活动类型
     * @return 用户活动日志列表
     */
    public List<UserActivityLog> getUserActivityLogsByType(String chatId, int eventId, String userId, String activityType) {
        String sql = "SELECT * FROM user_activity_logs WHERE chat_id = ? AND event_id = ? AND user_id = ? AND activity_type = ? " +
                    "ORDER BY activity_time DESC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                UserActivityLog log = new UserActivityLog();
                log.setLogId(rs.getInt("log_id"));
                log.setChatId(rs.getString("chat_id"));
                log.setEventId(rs.getInt("event_id"));
                log.setUserId(rs.getString("user_id"));
                log.setActivityType(rs.getString("activity_type"));
                log.setActivityTime(rs.getString("activity_time"));
                log.setActivityLog(rs.getString("activity_log"));
                log.setTopicId(rs.getInt("topic_id"));
                return log;
            }, chatId, eventId, userId, activityType);
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_LOGS_BY_TYPE_ERROR", "获取用户活动日志失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取用户在特定活动中发送的消息数量
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 用户ID
     * @return 用户消息数量
     */
    public int getUserMessageCount(String chatId, int eventId, String userId) {
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND event_id = ? AND user_id = ? AND " +
                    "activity_type IN ('text_message', 'photo_message', 'video_message', 'document_message', 'audio_message', 'sticker_message')";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, eventId, userId);
            return count != null ? count : 0;
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_MESSAGE_COUNT_ERROR", "获取用户消息数量失败: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 获取用户在特定活动中被互动的次数
     *
     * @param chatId 聊天ID
     * @param eventId 活动ID
     * @param userId 被互动的用户ID
     * @param interactionType 互动类型：message_reply 或 message_reaction
     * @return 被互动次数
     */
    public int getUserInteractionCount(String chatId, int eventId, String userId, String interactionType) {
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND event_id = ? AND activity_type = ? " +
                    "AND activity_log LIKE ?";
        try {
            String searchPattern = "%目标用户:" + userId + "%";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, eventId, interactionType, searchPattern);
            return count != null ? count : 0;
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_INTERACTION_COUNT_ERROR", "获取用户互动次数失败: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 记录用户首次加入群聊时间，如果已存在则不更新
     *
     * @param chatId 聊天ID
     * @param userId 用户ID
     * @param groupId 群组ID
     * @return 是否成功记录
     */
    public boolean recordUserJoinTime(String chatId, String userId, String groupId) {
        try {
            // 检查是否已存在记录
            String checkSql = "SELECT COUNT(*) FROM user_join_time WHERE chat_id = ? AND user_id = ? AND group_id = ?";
            int count = jdbcTemplate.queryForObject(checkSql, Integer.class, chatId, userId, groupId);
            
            if (count == 0) {
                // 不存在记录，添加新记录
                String insertSql = "INSERT INTO user_join_time (chat_id, user_id, group_id) VALUES (?, ?, ?)";
                int rows = jdbcTemplate.update(insertSql, chatId, userId, groupId);
                return rows > 0;
            }
            // 已存在记录，无需更新
            return true;
        } catch (Exception e) {
            LoggingUtils.logError("RECORD_JOIN_TIME_ERROR", "记录用户加入时间失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 获取用户加入群聊的时间
     *
     * @param chatId 聊天ID
     * @param userId 用户ID
     * @param groupId 群组ID
     * @return 加入时间，如果未找到则返回null
     */
    public String getUserJoinTime(String chatId, String userId, String groupId) {
        try {
            String sql = "SELECT join_time FROM user_join_time WHERE chat_id = ? AND user_id = ? AND group_id = ?";
            List<String> results = jdbcTemplate.queryForList(sql, String.class, chatId, userId, groupId);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            LoggingUtils.logError("GET_JOIN_TIME_ERROR", "获取用户加入时间失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 计算用户加入群聊的天数
     *
     * @param chatId 聊天ID
     * @param userId 用户ID
     * @param groupId 群组ID
     * @return 加入天数，如果未找到则返回0
     */
    public int getUserJoinDays(String chatId, String userId, String groupId) {
        try {
            String sql = "SELECT julianday('now') - julianday(join_time) AS days_joined FROM user_join_time WHERE chat_id = ? AND user_id = ? AND group_id = ?";
            List<Double> results = jdbcTemplate.queryForList(sql, Double.class, chatId, userId, groupId);
            if (results.isEmpty()) {
                return 0;
            }
            return (int) Math.floor(results.get(0));
        } catch (Exception e) {
            LoggingUtils.logError("GET_JOIN_DAYS_ERROR", "计算用户加入天数失败: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 获取全局成就列表
     * 
     * @return 全局成就列表
     */
    public List<Map<String, Object>> getGlobalAchievements() {
        String sql = "SELECT * FROM global_achievements";
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            LoggingUtils.logError("GET_GLOBAL_ACHIEVEMENTS_ERROR", "获取全局成就列表失败: " + e.getMessage(), e);
            return List.of();
        }
    }
    
    /**
     * 获取用户的全局成就
     *
     * @param chatId 聊天ID
     * @param userId 用户ID
     * @param achievementName 成就名称
     * @return 用户成就
     */
    public Map<String, Object> getUserGlobalAchievement(String chatId, String userId, String achievementName) {
        String sql = "SELECT * FROM user_achievements WHERE chat_id = ? AND user_id = ? AND achievement_name = ? AND is_global = 1";
        try {
            List<Map<String, Object>> achievements = jdbcTemplate.queryForList(sql, chatId, userId, String.valueOf(achievementName));
            return achievements.isEmpty() ? null : achievements.get(0);
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_GLOBAL_ACHIEVEMENT_ERROR", "获取用户全局成就失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 更新用户全局成就进度
     *
     * @param chatId 聊天ID
     * @param userId 用户ID
     * @param userName 用户名
     * @param achievementName 成就名称
     * @param ConditionCount 条件数
     * @return 是否更新成功
     */
    public boolean updateUserGlobalAchievement(String chatId, String userId, String userName, String achievementName, int ConditionCount) {
        try {
            Map<String, Object> userAchievement = getUserGlobalAchievement(chatId, userId, achievementName);
            LocalDateTime now = LocalDateTime.now();
            
            if (userAchievement == null) {
                // 用户没有该成就记录，创建新记录,并将成就等级设为0
                if ("Interaction Expert".equals(achievementName)) {
                    List<Map<String, Object>> userList = jdbcTemplate.queryForList(
                        "SELECT user_name FROM user_achievements WHERE user_id = ? AND is_global = 1 LIMIT 1", userId);
                    if (userList.isEmpty() || userList.get(0).get("user_name") == null) {
                        return false;
                    }
                    userName = userList.get(0).get("user_name").toString();
                }
                String insertSql = "INSERT INTO user_achievements (chat_id, user_id, user_name, achievement_name, progress, is_global,event_id) " +
                                 "VALUES (?, ?, ?, ?, ?, 1,0)";
                int rows = jdbcTemplate.update(insertSql, chatId, userId, userName, achievementName, ConditionCount);

                userAchievement = new HashMap<>();
                userAchievement.put("chat_id",chatId);
                userAchievement.put("user_id",userId);
                userAchievement.put("user_name",userName);
                userAchievement.put("achievement_name",achievementName);
                userAchievement.put("progress",ConditionCount);
                userAchievement.put("is_global",1);
                userAchievement.put("event_id",0);
            }
            if (Integer.parseInt(String.valueOf(userAchievement.get("event_id"))) < 5 ) { // 只对当前等级的下一级成就做出判断

                // 获取下一级等级id
                int achievementId = Integer.parseInt(String.valueOf(userAchievement.get("event_id"))) + 1;
                
                // 获取成就完成条件
                List<Map<String, Object>> achievements = jdbcTemplate.queryForList(
                    "SELECT * FROM global_achievements WHERE achievement_name = ? AND achievement_id = ?",
                    achievementName, achievementId);
                
                if (!achievements.isEmpty()) {
                    Map<String, Object> achievement = achievements.get(0);
                    int conditionCount = Integer.parseInt(achievement.get("condition_count").toString());
                    
                    if (ConditionCount >= conditionCount) {
                        // 成就已完成，记录完成时间
                        String updateSql = "UPDATE user_achievements SET progress = ?, complete_time = ?,event_id = ? " +
                                         "WHERE chat_id = ? AND user_id = ? AND achievement_name = ? AND is_global = 1";
                        int rows = jdbcTemplate.update(updateSql, 
                            ConditionCount,
                            now.format(formatter),
                            achievementId,
                            chatId, 
                            userId, 
                            achievementName);
                        return rows > 0;
                    }
                }
                
                // 成就未完成，仅更新进度
                String updateSql = "UPDATE user_achievements SET progress = ? " +
                                 "WHERE chat_id = ? AND user_id = ? AND achievement_name = ? AND is_global = 1";
                int rows = jdbcTemplate.update(updateSql, 
                    ConditionCount,
                    chatId, 
                    userId, 
                    achievementName);
                return rows > 0;
            } else {
                // 用户已完成该成就，不做更新
                return true;
            }
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_USER_GLOBAL_ACHIEVEMENT_ERROR", "更新用户全局成就失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 获取用户在全局范围内累计签到次数
     *
     * @param chatId 聊天ID
     * @param userId 用户ID
     * @return 累计签到次数
     */
    public int getUserGlobalCheckInCount(String chatId, String userId) {
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND user_id = ? AND activity_type = 'check_in'";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, userId);
            return count != null ? count : 0;
        } catch (Exception e) {
            LoggingUtils.logError("GET_GLOBAL_CHECK_IN_COUNT_ERROR", "获取用户全局签到次数失败: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 获取用户在全局范围内发送的消息数量
     *
     * @param chatId 聊天ID
     * @param userId 用户ID
     * @return 用户消息数量
     */
    public int getUserGlobalMessageCount(String chatId, String userId) {
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND user_id = ? AND " +
                    "activity_type IN ('text_message', 'photo_message', 'video_message', 'document_message', 'audio_message', 'sticker_message')";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, userId);
            return count != null ? count : 0;
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_GLOBAL_MESSAGE_COUNT_ERROR", "获取用户全局消息数量失败: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 获取用户在全局范围内被互动的次数
     * 被互动消息的通缉逻辑比较特殊，因为要找到的是被互动的用户而不是互动的用户，所以要在activity_log中根据模糊匹配来搜索
     *
     * @param chatId 聊天ID
     * @param userId 被互动的用户ID
     * @param interactionType 互动类型：message_reply 或 message_reaction
     * @return 被互动次数
     */
    public int getUserGlobalInteractionCount(String chatId, String userId, String interactionType) {
        String sql = "SELECT COUNT(*) FROM user_activity_logs WHERE chat_id = ? AND activity_type = ? " +
                    "AND activity_log LIKE ?";
        try {
            String searchPattern = "%目标用户:" + userId + "%";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, chatId, interactionType, searchPattern);
            return count != null ? count : 0;
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_GLOBAL_INTERACTION_COUNT_ERROR", "获取用户全局互动次数失败: " + e.getMessage(), e);
            return 0;
        }
    }
    /**
     * 根据消息ID查询用户ID
     * @param messageId
     * @return
     */
    public List<String> findUserIdByMessageId(Integer messageId){
        String sql = "SELECT user_id FROM log WHERE message_id = ?";
        return jdbcTemplate.queryForList(sql, new Object[]{messageId}, String.class);
    }

    /**
     * 根据消息ID查询log表，返回user_id和topic_id
     */
    public Optional<Map<String, Object>> findUserIdAndTopicIdByMessageId(Integer messageId) {
        String sql = "SELECT user_id, topic_id FROM log WHERE message_id = ? LIMIT 1";
        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, messageId);
            if (result.isEmpty()) return Optional.empty();
            return Optional.of(result.get(0));
        } catch (Exception e) {
            LoggingUtils.logError("FIND_USERID_TOPICID_ERROR", "根据messageId查找user_id和topic_id失败: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 获取所有群聊记录
     *
     * @return 群聊列表
     */
    public List<Map<String, Object>> getAllGroupChats() {
        String sql = "SELECT DISTINCT group_id, group_name FROM group_chat_records";
        return jdbcTemplate.queryForList(sql);
    }

    /**
     * 创建活动
     * @param event EventRecord对象
     * @return 是否创建成功
     */
    public boolean createEvent(EventRecord event) {
        String sql = "INSERT INTO event_records (event_name, event_description, start_time, end_time, event_group_id, admin_group_id, creator_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try {
            int rows = jdbcTemplate.update(sql,
                event.getEventName(),
                event.getEventDescription(),
                event.getStartTime(),
                event.getEndTime(),
                event.getEventGroupId(),
                event.getAdminGroupId(),
                event.getCreatorId()
            );
            return rows > 0;
        } catch (Exception e) {
            LoggingUtils.logError("CREATE_EVENT_ERROR", "创建活动失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 插入群聊记录
     * @param groupId 群聊ID
     * @param groupName 群聊名称
     * @param topicId 话题ID，可为null
     * @param topicName 话题名称，可为null
     * @return 是否插入成功
     */
    public boolean insertGroupChatRecord(String groupId, String groupName, Integer topicId, String topicName) {
        String sql = "INSERT INTO group_chat_records (group_id, group_name, topic_id, topic_name) VALUES (?, ?, ?, ?)";
        try {
            int rows = jdbcTemplate.update(sql, groupId, groupName, topicId, topicName);
            return rows > 0;
        } catch (Exception e) {
            LoggingUtils.logError("INSERT_GROUP_CHAT_RECORD_ERROR", "插入群聊记录失败: " + e.getMessage(), e);
            return false;
        }
    }

    public String getAdminGroup() {
        String sql = "SELECT group_id FROM admin_group LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, String.class);
        } catch (Exception e) {
            LoggingUtils.logError("GET_ADMIN_GROUP_ERROR", "获取管理群组失败: " + e.getMessage(), e);
            return null;
        }
    }

    public boolean insertAdminGroup(String adminGroupId) {
    String sql = "INSERT INTO admin_group (group_id) VALUES (?)";
        try {
            jdbcTemplate.update("DELETE FROM admin_group");
            int rows = jdbcTemplate.update(sql, adminGroupId);
            return rows > 0;
        } catch (Exception e) {
            LoggingUtils.logError("INSERT_ADMIN_GROUP_ERROR", "插入管理群组失败: " + e.getMessage(), e);
            return false;
        }
    }

    public static class GroupTopicMessage {
        public String groupId;
        public String groupName;
        public String topicId;
        public String topicName;
        public List<MessageItem> messages;
        public static class MessageItem {
            public String name;
            public String sendTime;
            public String text;
        }
    }

    public List<GroupTopicMessage> getGroupTopicMessagesByDate(java.time.LocalDate date) {
        String dayStr = date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String startTime = dayStr + " 00:00:00";
        String endTime = dayStr + " 23:59:59";
        List<Map<String, Object>> groupTopics = jdbcTemplate.queryForList("SELECT group_id, group_name, topic_id, topic_name FROM group_chat_records");
        List<GroupTopicMessage> result = new ArrayList<>();
        for (Map<String, Object> groupTopic : groupTopics) {
            LoggingUtils.logOperation( "GET_GROUP_TOPIC_MESSAGES_BY_DATE", "bot", "获取待总结的群聊,群聊名称" + String.valueOf(groupTopic.get("topic_name")));
            String groupId = String.valueOf(groupTopic.get("group_id"));
            String groupName = String.valueOf(groupTopic.get("group_name"));
            Object topicIdObj = groupTopic.get("topic_id");
            String topicId = topicIdObj != null ? String.valueOf(topicIdObj) : null;
            Object topicNameObj = groupTopic.get("topic_name");
            String topicName = topicNameObj != null ? String.valueOf(topicNameObj) : null;
            String topicIdCond = (topicIdObj == null) ? "IS NULL" : "= '" + topicIdObj + "'";
            String sql = String.format("SELECT user_name, send_time, message FROM log WHERE chat_id = ? AND topic_id %s AND send_time >= ? AND send_time <= ? ORDER BY send_time ASC", topicIdCond);
            List<Map<String, Object>> messages = jdbcTemplate.queryForList(sql, groupId, startTime, endTime);
//            记录sql信息和执行结果
            LoggingUtils.logOperation( "GET_GROUP_TOPIC_MESSAGES_BY_DATE", "bot",  "sql" + sql + "执行结果"+startTime +"--"+endTime + messages);
            if (messages.isEmpty()) continue;
            GroupTopicMessage gtm = new GroupTopicMessage();
            gtm.groupId = groupId;
            gtm.groupName = groupName;
            gtm.topicId = topicId;
            gtm.topicName = topicName;
            gtm.messages = new ArrayList<>();
            for (Map<String, Object> msg : messages) {
                GroupTopicMessage.MessageItem item = new GroupTopicMessage.MessageItem();
                item.name = (String) msg.get("user_name");
                item.sendTime = (String) msg.get("send_time");
                item.text = (String) msg.get("message");
                gtm.messages.add(item);
            }
            result.add(gtm);
        }
        return result;
    }

    public boolean updateGroupChatRecord(String groupId, String groupName, Integer topicId, String topicName) {
        String sql = "UPDATE group_chat_records SET group_name = ?, topic_name = ? WHERE group_id = ? AND (topic_id = ? OR (topic_id IS NULL AND ? IS NULL))";
        try {
        int rows = jdbcTemplate.update(sql, groupId, groupName, topicId, topicName);
        return rows > 0;
    } catch (Exception e) {
            LoggingUtils.logError("UPDATE_GROUP_CHAT_RECORD_ERROR", "更新群聊记录失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 判断群聊记录是否存在
     * @param groupId 群聊ID
     * @param topicId 话题ID，可为null
     * @return 是否存在
     */
    public boolean existsGroupChatRecord(String groupId, Integer topicId) {
        String sql = "SELECT COUNT(*) FROM group_chat_records WHERE group_id = ? AND (topic_id = ? OR (topic_id IS NULL AND ? IS NULL))";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, groupId, topicId, topicId);
            return count != null && count > 0;
        } catch (Exception e) {
            LoggingUtils.logError("EXISTS_GROUP_CHAT_RECORD_ERROR", "查询群聊记录是否存在失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 通过用户名查找用户ID
     *
     * @param userName 用户名
     * @return 用户ID，如果未找到则返回null
     */
    public String getUserIdByUserName(String userName) {
        String sql = "SELECT DISTINCT user_id FROM user_achievements WHERE user_name = ? LIMIT 1";
        try {
            List<String> results = jdbcTemplate.queryForList(sql, String.class, userName);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_ID_ERROR", "根据用户名获取用户ID失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取正在进行中和结束两天内的活动
     *
     * @return 活动记录列表
     */
    public List<EventRecord> getActiveAndRecentEndedEvents() {
        String sql = "SELECT * FROM event_records WHERE " +
                    "(datetime('now') BETWEEN datetime(start_time) AND datetime(end_time)) OR " +
                    "(datetime('now') BETWEEN datetime(end_time) AND datetime(end_time, '+2 days'))";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                EventRecord event = new EventRecord();
                event.setEventId(rs.getInt("event_id"));
                event.setEventName(rs.getString("event_name"));
                event.setEventDescription(rs.getString("event_description"));
                event.setStartTime(rs.getString("start_time"));
                event.setEndTime(rs.getString("end_time"));
                event.setEventGroupId(rs.getString("event_group_id"));
                event.setAdminGroupId(rs.getString("admin_group_id"));
                event.setCreatorId(rs.getString("creator_id"));
                return event;
            });
        } catch (Exception e) {
            LoggingUtils.logError("GET_ACTIVE_RECENT_EVENTS_ERROR", "获取活动列表失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取特定活动的所有用户积分记录
     *
     * @param eventId 活动ID
     * @return 用户积分记录列表
     */
    public List<Map<String, Object>> getAllUserPointsByEventId(int eventId) {
        String sql = "SELECT * FROM user_points WHERE event_id = ?";
        try {
            return jdbcTemplate.queryForList(sql, eventId);
        } catch (Exception e) {
            LoggingUtils.logError("GET_ALL_USER_POINTS_ERROR", "获取活动用户积分失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取用户的成就奖励加成
     *
     * @param userId 用户ID
     * @param eventId 活动ID
     * @return 成就加成倍数
     */
    public double getUserAchievementBonus(String userId, int eventId) {
        String sql = "SELECT ua.event_id, ga.reward FROM user_achievements ua " +
                    "JOIN global_achievements ga ON ua.achievement_name = ga.achievement_name AND ua.event_id = ga.achievement_id " +
                    "WHERE ua.user_id = ? AND ua.complete_time IS NOT NULL AND ua.is_global = 1";
        try {
            List<Map<String, Object>> achievements = jdbcTemplate.queryForList(sql, userId);
            double totalBonus = 1.0;
            for (Map<String, Object> achievement : achievements) {
                Object rewardObj = achievement.get("reward");
                if (rewardObj != null) {
                    double reward = Double.parseDouble(rewardObj.toString());
                    totalBonus += reward * 0.01; // reward值乘以0.01转换为加成百分比
                }
            }
            return totalBonus;
        } catch (Exception e) {
            LoggingUtils.logError("GET_ACHIEVEMENT_BONUS_ERROR", "获取用户成就加成失败: " + e.getMessage(), e);
            return 1.0;
        }
    }

    /**
     * 更新用户最终积分
     *
     * @param eventId 活动ID
     * @param userId 用户ID
     * @param aggregatePoints 最终积分
     * @return 是否更新成功
     */
    public boolean updateUserAggregatePoints(int eventId, String userId, double aggregatePoints) {
        String sql = "UPDATE user_points SET aggregate_points = ? WHERE event_id = ? AND user_id = ?";
        try {
            int rows = jdbcTemplate.update(sql, aggregatePoints, eventId, userId);
            return rows > 0;
        } catch (Exception e) {
            LoggingUtils.logError("UPDATE_AGGREGATE_POINTS_ERROR", "更新用户最终积分失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取用户在特定活动中的基础积分
     *
     * @param userId 用户ID
     * @param eventId 活动ID
     * @return 基础积分
     */
    public int getUserPoints(String userId, int eventId) {
        Map<String, Object> userPoints = getUserPoints(eventId, userId);
        if (userPoints == null) {
            return 0;
        }
        Object pointsObj = userPoints.get("points");
        return pointsObj != null ? Integer.parseInt(pointsObj.toString()) : 0;
    }

    /**
     * 获取用户在特定活动中的特殊积分
     *
     * @param userId 用户ID
     * @param eventId 活动ID
     * @return 特殊积分
     */
    public int getUserSpecialPoints(String userId, int eventId) {
        Map<String, Object> userPoints = getUserPoints(eventId, userId);
        if (userPoints == null) {
            return 0;
        }
        Object specialPointsObj = userPoints.get("special_points");
        return specialPointsObj != null ? Integer.parseInt(specialPointsObj.toString()) : 0;
    }

    /**
     * 获取用户已完成的成就列表
     *
     * @param userId 用户ID
     * @param chatId 群聊ID
     * @return 成就列表，包含成就名称和等级
     */
    public List<Map<String, Object>> getUserCompletedAchievements(String userId, String chatId) {
        String sql = "SELECT ua.achievement_name, ua.event_id FROM user_achievements ua " +
                    "WHERE ua.user_id = ? AND ua.chat_id = ? AND ua.complete_time IS NOT NULL AND ua.is_global = 1 " +
                    "ORDER BY ua.achievement_name, ua.event_id";
        try {
            return jdbcTemplate.queryForList(sql, userId, chatId);
        } catch (Exception e) {
            LoggingUtils.logError("GET_USER_ACHIEVEMENTS_ERROR", "获取用户成就列表失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取所有活动记录
     *
     * @return 活动记录列表
     */
    public List<EventRecord> getAllEvents() {
        String sql = "SELECT * FROM event_records ORDER BY event_id DESC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                EventRecord event = new EventRecord();
                event.setEventId(rs.getInt("event_id"));
                event.setEventName(rs.getString("event_name"));
                event.setEventDescription(rs.getString("event_description"));
                event.setStartTime(rs.getString("start_time"));
                event.setEndTime(rs.getString("end_time"));
                event.setEventGroupId(rs.getString("event_group_id"));
                event.setAdminGroupId(rs.getString("admin_group_id"));
                event.setCreatorId(rs.getString("creator_id"));
                return event;
            });
        } catch (Exception e) {
            LoggingUtils.logError("GET_ALL_EVENTS_ERROR", "获取所有活动失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取指定活动的积分排名（分页）
     *
     * @param eventId 活动ID
     * @param page 页码（从0开始）
     * @param pageSize 每页大小
     * @return 积分排名列表
     */
    public List<Map<String, Object>> getEventPointsRanking(int eventId, int page, int pageSize) {
        String sql = "SELECT up.user_id, up.user_name, up.points, up.special_points, " +
                    "CASE " +
                    "  WHEN up.aggregate_points IS NOT NULL AND up.aggregate_points != '' THEN CAST(up.aggregate_points AS REAL) " +
                    "  ELSE (up.points * COALESCE((SELECT 1.0 + SUM(CAST(ga.reward AS REAL) * 0.01) " +
                    "                             FROM user_achievements ua " +
                    "                             JOIN global_achievements ga ON ua.achievement_name = ga.achievement_name AND ua.event_id = ga.achievement_id " +
                    "                             WHERE ua.user_id = up.user_id AND ua.complete_time IS NOT NULL AND ua.is_global = 1), 1.0) + up.special_points) " +
                    "END AS final_points " +
                    "FROM user_points up " +
                    "WHERE up.event_id = ? " +
                    "AND up.user_id NOT IN (SELECT user_id FROM admin_user) " +
                    "ORDER BY final_points DESC " +
                    "LIMIT ? OFFSET ?";
        try {
            return jdbcTemplate.queryForList(sql, eventId, pageSize, page * pageSize);
        } catch (Exception e) {
            LoggingUtils.logError("GET_EVENT_POINTS_RANKING_ERROR", "获取活动积分排名失败: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取指定活动的用户总数（排除管理员）
     *
     * @param eventId 活动ID
     * @return 用户总数
     */
    public int getEventUserCount(int eventId) {
        String sql = "SELECT COUNT(*) FROM user_points WHERE event_id = ? AND user_id NOT IN (SELECT user_id FROM admin_user)";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, eventId);
            return count != null ? count : 0;
        } catch (Exception e) {
            LoggingUtils.logError("GET_EVENT_USER_COUNT_ERROR", "获取活动用户数量失败: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 根据活动ID获取活动信息
     *
     * @param eventId 活动ID
     * @return 活动信息
     */
    public EventRecord getEventById(int eventId) {
        String sql = "SELECT * FROM event_records WHERE event_id = ?";
        try {
            List<EventRecord> events = jdbcTemplate.query(sql, (rs, rowNum) -> {
                EventRecord event = new EventRecord();
                event.setEventId(rs.getInt("event_id"));
                event.setEventName(rs.getString("event_name"));
                event.setEventDescription(rs.getString("event_description"));
                event.setStartTime(rs.getString("start_time"));
                event.setEndTime(rs.getString("end_time"));
                event.setEventGroupId(rs.getString("event_group_id"));
                event.setAdminGroupId(rs.getString("admin_group_id"));
                event.setCreatorId(rs.getString("creator_id"));
                return event;
            }, eventId);
            return events.isEmpty() ? null : events.get(0);
        } catch (Exception e) {
            LoggingUtils.logError("GET_EVENT_BY_ID_ERROR", "根据ID获取活动失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取所有管理员用户名列表
     * @return 管理员用户名列表
     */
    public List<String> getAdminUserNames() {
        String sql = "SELECT user_name FROM admin_user WHERE user_name IS NOT NULL";
        try {
            return jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            LoggingUtils.logError("GET_ADMIN_USER_NAMES_ERROR", "获取管理员用户名列表失败: " + e.getMessage(), e);
            return List.of();
        }
    }
}