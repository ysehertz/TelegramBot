package com.bot.aabot.entity;

import lombok.Builder;
import lombok.Data;

/**
 * ClassName: UpLogEntity
 * Package: com.bot.aabot.entity
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/3/19
 */
@Builder
@Data
public class UpLogEntity {
    /**
     * String sql = "INSERT INTO message_logs (form_name, message_id, user_id, user_name, message_type, message) "
     *           + "VALUES (?, ?, ?, ?, ?, ?)";
     */
    private String formName;
    private Integer messageId;
    private Long userId;
    private String userName;
    private String messageType;
    private String message;
    private Integer sendTime;
    private Long chatId;
    private Integer topicId;
}
