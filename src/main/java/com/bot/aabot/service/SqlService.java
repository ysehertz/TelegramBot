package com.bot.aabot.service;

import com.bot.aabot.context.DataContext;
import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.UpLogEntity;
import com.bot.aabot.utils.SQLiteUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Comparator;

/**
 * ClassName: SqlService
 * Package: com.bot.aabot.service
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/3/19
 */
@Service
public class SqlService {
    @Autowired
    GPTService gptService;
    @Autowired
    private SQLiteUtil sqLiteUtil;
    @Autowired
    JdbcTemplate jdbcTemplate;
    public void saveMessage(Update update) {
        Message message = update.getMessage();
        String sql = "INSERT INTO "+ DataContext.tableName +" (form_name, message_id, user_id, user_name, message_type, message, send_time) "
                + "VALUES (?, ?, ?, ?, ?, ?, datetime(? , 'unixepoch', 'localtime'))";
        UpLogEntity upLogEntity = new UpLogEntity();
        upLogEntity.setMessageId(message.getMessageId());
        upLogEntity.setUserId(message.getFrom().getId());
        upLogEntity.setUserName(message.getFrom().getUserName());
        upLogEntity.setFormName(update.getMessage().getChat().getTitle());
        upLogEntity.setSendTime(message.getDate());
        if(message.hasPhoto()){
            upLogEntity.setMessageType("photo");
            String f_id = update.getMessage().getPhoto().stream().max(Comparator.comparing(PhotoSize::getFileSize))
                    .map(PhotoSize::getFileId)
                    .orElse("");
            upLogEntity.setMessage(update.getMessage().getCaption()+"["+f_id+"]");
        }else if(message.hasText()){
            upLogEntity.setMessageType("text");
            upLogEntity.setMessage(message.getText());
        }else if(message.hasVideo()){
            upLogEntity.setMessageType("video");
            upLogEntity.setMessage(message.getVideo().getFileId());
        }else if (message.hasAnimation()){
            upLogEntity.setMessageType("animation");
            upLogEntity.setMessage(message.getAnimation().getFileId());
        }else if(message.hasDocument()){
            upLogEntity.setMessageType("document");
            upLogEntity.setMessage(message.getDocument().getFileName()+"["+message.getDocument().getFileId()+"]");
        }
        if(upLogEntity.getMessageType() == null){
            upLogEntity.setMessageType("other");
            upLogEntity.setMessage("other");
        }
        jdbcTemplate.update(sql, upLogEntity.getFormName(), upLogEntity.getMessageId(), upLogEntity.getUserId(), upLogEntity.getUserName(), upLogEntity.getMessageType(), upLogEntity.getMessage(), upLogEntity.getSendTime());

    }

    public void editMessage(Update update){
        Integer messageId = update.getEditedMessage().getMessageId();
        String sql = "UPDATE "+DataContext.tableName+" SET is_edit = 1 WHERE message_id =" + messageId;
        jdbcTemplate.update(sql);
    }

    public SendMessage resMessage(Update update){
        if(gptService.isQuestion(update.getMessage().getText())){
            // 构建user为群聊id加用户id
            String user = update.getMessage().getChatId()+"_"+update.getMessage().getFrom().getId();
            GPTAnswer gptAnswer = gptService.answerUserQuestion(user,update.getMessage().getText());
            SendMessage message = SendMessage // Create a message object
                    .builder()
                    .chatId(update.getMessage().getChatId())
                    .replyToMessageId(update.getMessage().getMessageId())
                    .text(gptAnswer.getAnswer())
                    .build();
            String sql = "INSERT INTO "+ DataContext.resTableName +" (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                    + "VALUES (?, ?, ?, ?, ?,?)";
            jdbcTemplate.update(sql, update.getMessage().getText(), update.getMessage().getMessageId(), update.getMessage().getFrom().getUserName(), update.getMessage().getFrom().getId(), gptAnswer.getAnswer(), gptAnswer.getSessionId());
            return message;
        }
        return null;
    }
}
