package com.bot.aabot.entity;

import lombok.Data;

/**
 * ClassName: UserAchievement
 * Package: com.bot.aabot.entity
 * Description: 用户成就实体类
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/27
 */
@Data
public class UserAchievement {
    /**
     * 用户成就ID
     */
    private Integer achievementId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 成就名称
     */
    private String achievementName;
    
    /**
     * 完成进度
     */
    private Integer progress;
    
    /**
     * 完成时间
     */
    private String completeTime;
    
    /**
     * 活动ID
     */
    private Integer eventId;
    
    /**
     * 群聊ID
     */
    private String chatId;
} 