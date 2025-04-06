package com.bot.aabot.service;

import com.bot.aabot.context.DataContext;
import com.bot.aabot.context.MessageContext;
import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.entity.UpLogEntity;
import com.bot.aabot.utils.SQLiteUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
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
    private ApplicationContext applicationContext;
    @Autowired
    GPTService gptService;
    @Value("${bot.name}")
    String botName;
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
            boolean isAit = false;
            // 检查用户是否@了机器人
            if (message.getEntities() != null && !message.getEntities().isEmpty()) {
                for (var entity : message.getEntities()) {
                    if ("mention".equals(entity.getType())) {
                        String mention = message.getText().substring(entity.getOffset(), entity.getOffset() + entity.getLength());
                        // 如果提及的是当前机器人的用户名
                        if (mention.startsWith("@") && mention.substring(1).equals(botName)) {
                            isAit = true;
                            resMessage(update);
                        }
                    }
                }
            }
            if(!isAit)
                localSaveMessage(update);
//            saveMessage(update);
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


    // 本地保存消息
    public void localSaveMessage(Update update) {
        TextMessageEntity textMessageEntity = TextMessageEntity.builder()
                .sessionId(update.getMessage().getChatId())
                .messageId(update.getMessage().getMessageId())
                .content(update.getMessage().getText())
                .sendTime(String.valueOf(update.getMessage().getDate()))
                .isQuestion(gptService.isQuestion(update.getMessage().getText()))
                .update(update)
                .build();
        MessageContext.messageContextList.add(textMessageEntity);
    }

    public void editMessage(Update update){
        Integer messageId = update.getEditedMessage().getMessageId();
        String sql = "UPDATE "+DataContext.tableName+" SET is_edit = 1 WHERE message_id =" + messageId;
        jdbcTemplate.update(sql);
    }

    public SendMessage directResMessage(TextMessageEntity textMessageEntity){
        String user = textMessageEntity.getSessionId()+"_"+textMessageEntity.getMessageId();
        GPTAnswer gptAnswer = gptService.answerUserQuestion(user,textMessageEntity.getContent());
        SendMessage message = SendMessage // Create a message object
                .builder()
                .chatId(textMessageEntity.getSessionId())
                .replyToMessageId(textMessageEntity.getMessageId())
                .text(gptAnswer.getAnswer())
                .build();
        String sql = "INSERT INTO "+ DataContext.resTableName +" (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                + "VALUES (?, ?, ?, ?, ?,?)";
        jdbcTemplate.update(sql, textMessageEntity.getUpdate().getMessage().getText(), textMessageEntity.getMessageId(), textMessageEntity.getUpdate().getMessage().getFrom().getUserName(), textMessageEntity.getUpdate().getMessage().getFrom().getId(), gptAnswer.getAnswer(), gptAnswer.getSessionId());
        return message;
    }
    public void resMessage(Update update){
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
            try {
                // 使用ApplicationContext在需要时获取MyAmazingBot的bean
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
