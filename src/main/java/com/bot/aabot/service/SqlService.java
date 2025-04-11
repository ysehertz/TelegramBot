package com.bot.aabot.service;

import com.bot.aabot.context.DataContext;
import com.bot.aabot.context.MessageContext;
import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.GuideMessage;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
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
                            // 去除@botName
                            String text = message.getText().substring(entity.getOffset() + entity.getLength()).trim();
                            isAit = true;
                            // 直接回复消息
                            TextMessageEntity textMessageEntity = TextMessageEntity.builder()
                                    .sessionId(update.getMessage().getChatId())
                                    .messageId(update.getMessage().getMessageId())
                                    .content(text)
                                    .sendTime(String.valueOf(update.getMessage().getDate()))
                                    .isQuestion(gptService.isQuestion(update.getMessage().getText()))
                                    .update(update)
                                    .build();
                            aitMessage(textMessageEntity);
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

    public void aitMessage(TextMessageEntity textMessageEntity){
        String user = textMessageEntity.getSessionId()+"_"+textMessageEntity.getUpdate().getMessage().getFrom().getId();
        GPTAnswer gptAnswer = gptService.answerUserQuestionWithAit(user,textMessageEntity.getContent());
        if(gptAnswer.getAnswer() != null && !gptAnswer.getAnswer().isEmpty()){
            GuideMessage guideMessage = parseGuideMessageFromJson(gptAnswer.getAnswer());
            // 构建使用GuideMessage结构的回复
            SendMessage message = SendMessage
                    .builder()
                    .chatId(textMessageEntity.getSessionId())
                    .replyToMessageId(textMessageEntity.getMessageId())
                    .text(guideMessage.getReply())
                    .replyMarkup(InlineKeyboardMarkup
                            .builder()
                            .keyboardRow(new InlineKeyboardRow(
                                    InlineKeyboardButton
                                            .builder()
                                            .text(guideMessage.getGuide1())
                                            .callbackData("guide1")
                                            .build()
                            ))
                            .keyboardRow(new InlineKeyboardRow(
                                    InlineKeyboardButton
                                            .builder()
                                            .text(guideMessage.getGuide2())
                                            .callbackData("guide2")
                                            .build()
                            ))
                            .keyboardRow(new InlineKeyboardRow(
                                    InlineKeyboardButton
                                            .builder()
                                            .text(guideMessage.getGuide3())
                                            .callbackData("guide3")
                                            .build()
                            ))
                            .build())
                    .build();
                    
            try {
                // 使用ApplicationContext在需要时获取MyAmazingBot的bean
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void directResMessage(  TextMessageEntity textMessageEntity){
        String user = textMessageEntity.getSessionId()+"_"+textMessageEntity.getUpdate().getMessage().getFrom().getId();
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
        try {
            // 使用ApplicationContext在需要时获取MyAmazingBot的bean
            Object bot = applicationContext.getBean("myAmazingBot");
            bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
        } catch (Exception e) {
            e.printStackTrace();
        }
//        return message;
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

    /**
     * 将JSON格式的GPT回答解析为GuideMessage对象
     * @param jsonResponse GPT回答的JSON字符串
     * @return GuideMessage对象
     */
    private GuideMessage parseGuideMessageFromJson(String jsonResponse) {
        try {
            // 使用Jackson的ObjectMapper解析JSON
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return objectMapper.readValue(jsonResponse, GuideMessage.class);
        } catch (Exception e) {
            // 记录详细的错误信息和JSON内容
            System.err.println("解析JSON失败: " + e.getMessage());
            System.err.println("尝试解析的JSON: " + jsonResponse);
            e.printStackTrace();
            
            // 尝试手动解析JSON
            try {
                return parseJsonManually(jsonResponse);
            } catch (Exception manualEx) {
                System.err.println("手动解析JSON也失败: " + manualEx.getMessage());
                manualEx.printStackTrace();
                
                // 解析失败时，创建默认的GuideMessage对象
                return GuideMessage.builder()
                        .reply("Failed to parse response: " + e.getMessage())
                        .guide1("Try rephrasing your question")
                        .guide2("Ask a more specific question")
                        .guide3("Provide more context")
                        .build();
            }
        }
    }
    
    /**
     * 手动解析JSON字符串为GuideMessage对象
     * @param jsonStr JSON字符串
     * @return GuideMessage对象
     */
    private GuideMessage parseJsonManually(String jsonStr) {
        // 清理JSON字符串，移除可能的前后缀
        jsonStr = jsonStr.trim();
        if (jsonStr.startsWith("{") && jsonStr.endsWith("}")) {
            // 提取回复和指导短语
            String reply = extractField(jsonStr, "reply");
            String guide1 = extractField(jsonStr, "guide1");
            String guide2 = extractField(jsonStr, "guide2");
            String guide3 = extractField(jsonStr, "guide3");
            
            // 创建GuideMessage对象
            return new GuideMessage(reply, guide1, guide2, guide3);
        }
        throw new IllegalArgumentException("无效的JSON格式");
    }
    
    /**
     * 从JSON字符串中提取指定字段的值
     * @param jsonStr JSON字符串
     * @param fieldName 字段名
     * @return 字段值
     */
    private String extractField(String jsonStr, String fieldName) {
        String searchStr = "\"" + fieldName + "\"";
        int fieldIndex = jsonStr.indexOf(searchStr);
        if (fieldIndex == -1) {
            return ""; // 字段不存在
        }
        
        // 找到字段值的起始位置
        int valueStart = jsonStr.indexOf(':', fieldIndex) + 1;
        while (valueStart < jsonStr.length() && 
               (jsonStr.charAt(valueStart) == ' ' || jsonStr.charAt(valueStart) == '\"')) {
            valueStart++;
        }
        
        // 找到字段值的结束位置
        int valueEnd;
        if (jsonStr.charAt(valueStart - 1) == '\"') {
            // 字符串值，查找闭合的引号
            valueEnd = jsonStr.indexOf('\"', valueStart);
        } else {
            // 非字符串值，查找逗号或大括号
            valueEnd = jsonStr.indexOf(',', valueStart);
            if (valueEnd == -1) {
                valueEnd = jsonStr.indexOf('}', valueStart);
            }
        }
        
        if (valueEnd == -1) {
            return ""; // 无法找到字段值的结束位置
        }
        
        return jsonStr.substring(valueStart - 1, valueEnd + 1).replace("\"", "");
    }

    public void callbackQuery(Update update) {

    }
}
