package com.bot.aabot.entity;

import lombok.Builder;
import lombok.Data;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * ClassName: MessageEntity
 * Package: com.bot.aabot.entity
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/1
 */
@Builder
@Data
public class TextMessageEntity {
    // 发送时间
    private String sendTime;
    // 会话id
    private String sessionId;
    // 消息id
    private Integer messageId;
    // 消息内容
    private String content;
    // 是否是一个问题
    private boolean isQuestion;
    // 回复消息
    private String replyMessage;

    private Update update;
}
