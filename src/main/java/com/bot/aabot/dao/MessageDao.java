package com.bot.aabot.dao;

import com.bot.aabot.context.DataContext;
import com.bot.aabot.entity.GuideMessage;
import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.entity.UpLogEntity;
import com.bot.aabot.utils.LoggingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * ClassName: messageDco
 * Package: com.bot.aabot.dao
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/9/4
 */
@Repository
public class MessageDao {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 保存回复信息
     * @param guideMessage
     * @param update
     * @param user
     * @throws Exception
     */
    public void saveReply(GuideMessage guideMessage, Update update, String user ) throws Exception{
        String sql = "INSERT INTO "+ DataContext.resTableName +" (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                + "VALUES (?, ?, ?, ?, ?,?)";
        // 将GuideMessage转换为JSON字符串用于数据库存储
        String gptResponse = String.format("{\"reply\":\"%s\",\"guide1\":\"%s\",\"guide2\":\"%s\",\"guide3\":\"%s\"}",
                guideMessage.getReply(), guideMessage.getGuide1(), guideMessage.getGuide2(), guideMessage.getGuide3());
        jdbcTemplate.update(sql, update.getMessage().getText(), update.getMessage().getMessageId(), update.getMessage().getFrom().getUserName(), update.getMessage().getFrom().getId(), gptResponse, user);
    }

    /**
     * 保存消息
     * @param upLogEntity
     */
    public void saveMessages(UpLogEntity upLogEntity){
        String sql = "INSERT INTO "+ DataContext.tableName +" (form_name, message_id, user_id, user_name, message_type, message, send_time, chat_id, topic_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, datetime(? , 'unixepoch', 'localtime'), ?, ?)";
        jdbcTemplate.update(sql,
                upLogEntity.getFormName(),
                upLogEntity.getMessageId(),
                upLogEntity.getUserId(),
                upLogEntity.getUserName(),
                upLogEntity.getMessageType(),
                upLogEntity.getMessage(),
                upLogEntity.getSendTime(),
                upLogEntity.getChatId(),
                upLogEntity.getTopicId());
    }
    /**
     * 修改消息被修改值的状态
     * @param update
     * @throws Exception
     */
    public void editMessage(Update update) throws Exception{
        Integer messageId = update.getEditedMessage().getMessageId();
        String sql = "UPDATE "+DataContext.tableName+" SET is_edit = 1 WHERE message_id =" + messageId;
        jdbcTemplate.update(sql);
    }
}
