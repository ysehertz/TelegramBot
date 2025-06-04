package com.bot.aabot.entity;

import lombok.Data;

/**
 * ClassName: EventRecord
 * Package: com.bot.aabot.entity
 * Description: 活动记录实体类
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/27
 */
@Data
public class EventRecord {
    /**
     * 活动创建者id     
     */
    private String creatorId;

    /**
     * 活动ID
     */
    private Integer eventId;
    
    /**
     * 活动名称
     */
    private String eventName;
    
    /**
     * 活动描述
     */
    private String eventDescription;
    
    /**
     * 开始时间
     */
    private String startTime;
    
    /**
     * 结束时间
     */
    private String endTime;
    
    /**
     * 活动所在群组ID
     */
    private String eventGroupId;
    
    /**
     * 管理员群组ID
     */
    private String adminGroupId;
} 