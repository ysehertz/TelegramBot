package com.bot.aabot.entity;

import lombok.Data;

/**
 * ClassName: UserActivityLog
 * Package: com.bot.aabot.entity
 * Description: 用户活动日志实体类
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/27
 */
@Data
public class UserActivityLog {
    /**
     * 日志ID
     */
    private Integer logId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 活动类型
     */
    private String activityType;
    
    /**
     * 活动时间
     */
    private String activityTime;
    
    /**
     * 活动日志内容
     */
    private String activityLog;
    
    /**
     * 活动ID
     */
    private Integer eventId;
    
    /**
     * 话题ID
     */
    private Integer topicId;
    
    /**
     * 群聊ID
     */
    private String chatId;
} 