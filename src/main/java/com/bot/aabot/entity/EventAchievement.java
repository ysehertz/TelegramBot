package com.bot.aabot.entity;

import lombok.Data;

/**
 * ClassName: EventAchievement
 * Package: com.bot.aabot.entity
 * Description: 活动成就实体类
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/27
 */
@Data
public class EventAchievement {
    /**
     * 活动ID
     */
    private Integer eventId;
    
    /**
     * 成就名称
     */
    private String achievementName;
    
    /**
     * 成就ID
     */
    private String achievementId;
    
    /**
     * 成就描述
     */
    private String achievementDescription;
    
    /**
     * 成就类型
     */
    private String achievementType;
    
    /**
     * 完成条件次数
     */
    private Integer conditionCount;
    
    /**
     * 奖励
     */
    private String reward;
} 