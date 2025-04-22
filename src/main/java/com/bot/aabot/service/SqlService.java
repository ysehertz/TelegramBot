package com.bot.aabot.service;

import com.bot.aabot.context.DataContext;
import com.bot.aabot.context.MessageContext;
import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.GuideMessage;
import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.entity.UpLogEntity;
import com.bot.aabot.utils.LoggingUtils;
import com.bot.aabot.utils.SQLiteUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
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
@Slf4j
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
        long startTime = System.currentTimeMillis();
        try {
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
            LoggingUtils.logOperation("SAVE_MESSAGE", String.valueOf(upLogEntity.getUserId()), "保存消息成功");
            LoggingUtils.logPerformance("saveMessage", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("SAVE_MESSAGE_ERROR", "保存消息失败", e);
        }
    }

    /**
     * 处理回调查询
     * @param update 更新对象
     */
    public void callbackQuery(Update update) {
        if ("click the button below to learn more:".equals(update.getCallbackQuery().getData())){
            return;
        }
        long startTime = System.currentTimeMillis();
        try {
            Long senderId = null;
            Integer messageId = null;
            try {
                MaybeInaccessibleMessage message = update.getCallbackQuery().getMessage();
                if (message instanceof Message) {
                    Message concreteMessage = (Message) message;
                    if (concreteMessage.getReplyToMessage() != null) {
                        senderId = concreteMessage.getReplyToMessage().getFrom().getId();
                        messageId = concreteMessage.getReplyToMessage().getMessageId();
                    } else {
                        throw new Exception("消息没有回复");
                    }
                } else {
                    LoggingUtils.logError("CALLBACK_ERROR", "无法访问消息内容或消息不是标准Message类型", null);
                }
            } catch (Exception e) {
                LoggingUtils.logError("CALLBACK_ERROR", "获取回复消息发送者ID时出错", e);
            }
            if (update.getCallbackQuery().getFrom().getId().equals(senderId) ){
                callbackMessage(update,messageId);
            }else {
                resRefuseMessage(update);
            }
            LoggingUtils.logPerformance("callbackQuery", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("CALLBACK_ERROR", "处理回调查询失败", e);
        }
    }

    // 本地保存消息
    public void localSaveMessage(Update update) {
        try {
            TextMessageEntity textMessageEntity = TextMessageEntity.builder()
                    .sessionId(update.getMessage().getChatId())
                    .messageId(update.getMessage().getMessageId())
                    .content(update.getMessage().getText())
                    .sendTime(String.valueOf(update.getMessage().getDate()))
                    .isQuestion(gptService.isQuestion(update.getMessage().getText()))
                    .update(update)
                    .build();
            MessageContext.messageContextList.add(textMessageEntity);
            LoggingUtils.logOperation("LOCAL_SAVE", String.valueOf(update.getMessage().getFrom().getId()), "本地保存消息成功");
        } catch (Exception e) {
            LoggingUtils.logError("LOCAL_SAVE_ERROR", "本地保存消息失败", e);
        }
    }

    public void editMessage(Update update){
        try {
            Integer messageId = update.getEditedMessage().getMessageId();
            String sql = "UPDATE "+DataContext.tableName+" SET is_edit = 1 WHERE message_id =" + messageId;
            jdbcTemplate.update(sql);
            LoggingUtils.logOperation("EDIT_MESSAGE", String.valueOf(update.getEditedMessage().getFrom().getId()), "更新消息编辑状态成功");
        } catch (Exception e) {
            LoggingUtils.logError("EDIT_MESSAGE_ERROR", "更新消息编辑状态失败", e);
        }
    }

    public void callbackMessage(Update update,Integer messageId){
        long startTime = System.currentTimeMillis();
        try {
            String user = update.getCallbackQuery().getMessage().getChatId()+"_"+update.getCallbackQuery().getFrom().getId();
            GPTAnswer gptAnswer = gptService.answerUserQuestionWithAit(user,update.getCallbackQuery().getData());
            GuideMessage guideMessage = parseGuideMessageFromJson(gptAnswer.getAnswer());
            // 构建使用GuideMessage结构的回复
            SendMessage message = SendMessage
                    .builder()
                    .chatId(update.getCallbackQuery().getMessage().getChatId())
                    .replyToMessageId(messageId)
                    .text(update.getCallbackQuery().getData()+" : "+guideMessage.getReply())
                    .replyMarkup(InlineKeyboardMarkup
                            .builder()
                            .keyboardRow(new InlineKeyboardRow(
                                    InlineKeyboardButton
                                            .builder()
                                            .text(guideMessage.getGuide1())
                                            .callbackData(guideMessage.getGuide1())
                                            .build()
                                    ,
                                    InlineKeyboardButton
                                            .builder()
                                            .text(guideMessage.getGuide2())
                                            .callbackData(guideMessage.getGuide2())
                                            .build()
                            ))
//                            .keyboardRow(new InlineKeyboardRow(
//                                    InlineKeyboardButton
//                                            .builder()
//                                            .text(guideMessage.getGuide2())
//                                            .callbackData(guideMessage.getGuide2())
//                                            .build()
//                            ))
//                            .keyboardRow(new InlineKeyboardRow(
//                                    InlineKeyboardButton
//                                            .builder()
//                                            .text(guideMessage.getGuide3())
//                                            .callbackData(guideMessage.getGuide3())
//                                            .build()
//                            ))
                            .build())
                    .build();
            String sql = "INSERT INTO "+ DataContext.resTableName +" (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                    + "VALUES (?, ?, ?, ?, ?,?)";
            jdbcTemplate.update(sql, update.getCallbackQuery().getData(), update.getCallbackQuery().getMessage().getMessageId(), update.getCallbackQuery().getFrom().getUserName(), update.getCallbackQuery().getFrom().getId(), gptAnswer.getAnswer(), gptAnswer.getSessionId());
            try {
                // 使用ApplicationContext在需要时获取MyAmazingBot的bean
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
                LoggingUtils.logOperation("CALLBACK_MESSAGE", String.valueOf(update.getCallbackQuery().getFrom().getId()), "处理回调查询成功");
            } catch (Exception e) {
                LoggingUtils.logError("BOT_REPLY_ERROR", "机器人回复消息失败", e);
            }
            LoggingUtils.logPerformance("callbackMessage", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("CALLBACK_MESSAGE_ERROR", "处理回调查询失败", e);
        }
    }

    public void aitMessage(TextMessageEntity textMessageEntity){
        long startTime = System.currentTimeMillis();
        try {
            String user = textMessageEntity.getSessionId()+"_"+textMessageEntity.getUpdate().getMessage().getFrom().getId();
            LoggingUtils.logBusinessOperation("AI_RESPONSE", user, "开始处理@回复: " + textMessageEntity.getContent());
            
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
                                                .text("click the button below to learn more:")
//                                                .callbackData(guideMessage.getGuide1())
                                                .callbackData("click the button below to learn more:")
                                                .build()
                                ))
                                .keyboardRow(new InlineKeyboardRow(
                                        InlineKeyboardButton
                                                .builder()
                                                .text(guideMessage.getGuide1())
                                                .callbackData(guideMessage.getGuide1())
                                                .build(),
                                        InlineKeyboardButton
                                                .builder()
                                                .text(guideMessage.getGuide2())
                                                .callbackData(guideMessage.getGuide2())
                                                .build()
//                                ))
//                                .keyboardRow(new InlineKeyboardRow(
//                                        InlineKeyboardButton
//                                                .builder()
//                                                .text(guideMessage.getGuide2())
//                                                .callbackData(guideMessage.getGuide2())
//                                                .build()
//                                ))
//                                .keyboardRow(new InlineKeyboardRow(
//                                        InlineKeyboardButton
//                                                .builder()
//                                                .text(guideMessage.getGuide3())
//                                                .callbackData(guideMessage.getGuide3())
//                                                .build()
//                                )
                                )                               )
                                .build())
                        .build();
                String sql = "INSERT INTO "+ DataContext.resTableName +" (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                        + "VALUES (?, ?, ?, ?, ?,?)";
                jdbcTemplate.update(sql, textMessageEntity.getUpdate().getMessage().getText(), textMessageEntity.getMessageId(), textMessageEntity.getUpdate().getMessage().getFrom().getUserName(), textMessageEntity.getUpdate().getMessage().getFrom().getId(), gptAnswer.getAnswer(), gptAnswer.getSessionId());
                LoggingUtils.logOperation("DB_OPERATION", user, "保存AI回复到数据库");
                
                try {
                    // 使用ApplicationContext在需要时获取MyAmazingBot的bean
                    Object bot = applicationContext.getBean("myAmazingBot");
                    bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
                    LoggingUtils.logOperation("SEND_REPLY", user, "成功发送AI回复消息");
                } catch (Exception e) {
                    LoggingUtils.logError("BOT_REPLY_ERROR", "机器人回复消息失败", e);
                }
                LoggingUtils.logPerformance("aitMessage", startTime);
            } else {
                LoggingUtils.logError("EMPTY_RESPONSE", "GPT返回空回复", new Exception("Empty response"));
            }
        } catch (Exception e) {
            LoggingUtils.logError("AIT_MESSAGE_ERROR", "处理@回复消息失败", e);
        }
    }

    public void directResMessage(TextMessageEntity textMessageEntity){
        if ("click the button below to learn more:".equals(textMessageEntity.getContent())){
            return;
        }
        long startTime = System.currentTimeMillis();
        try {
            String user = textMessageEntity.getSessionId()+"_"+textMessageEntity.getUpdate().getMessage().getFrom().getId();
            LoggingUtils.logBusinessOperation("DIRECT_RESPONSE", user, "开始处理直接回复: " + textMessageEntity.getContent());
            
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
            LoggingUtils.logOperation("DB_OPERATION", user, "保存直接回复到数据库");
            
            try {
                // 使用ApplicationContext在需要时获取MyAmazingBot的bean
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
                LoggingUtils.logOperation("SEND_REPLY", user, "成功发送直接回复消息");
            } catch (Exception e) {
                LoggingUtils.logError("BOT_REPLY_ERROR", "机器人直接回复消息失败", e);
            }
            LoggingUtils.logPerformance("directResMessage", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("DIRECT_MESSAGE_ERROR", "处理直接回复消息失败", e);
        }
    }

    public void resMessage(Update update){
        long startTime = System.currentTimeMillis();
        try {
            if(gptService.isQuestion(update.getMessage().getText())){
                // 构建user为群聊id加用户id
                String user = update.getMessage().getChatId()+"_"+update.getMessage().getFrom().getId();
                LoggingUtils.logBusinessOperation("AUTO_RESPONSE", user, "开始处理自动回复: " + update.getMessage().getText());
                
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
                LoggingUtils.logOperation("DB_OPERATION", user, "保存自动回复到数据库");
                
                try {
                    // 使用ApplicationContext在需要时获取MyAmazingBot的bean
                    Object bot = applicationContext.getBean("myAmazingBot");
                    bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
                    LoggingUtils.logOperation("SEND_REPLY", user, "成功发送自动回复消息");
                } catch (Exception e) {
                    LoggingUtils.logError("BOT_REPLY_ERROR", "机器人自动回复消息失败", e);
                }
                LoggingUtils.logPerformance("resMessage", startTime);
            } else {
                LoggingUtils.logOperation("NOT_QUESTION", String.valueOf(update.getMessage().getFrom().getId()), "消息不是问题，忽略: " + update.getMessage().getText());
            }
        } catch (Exception e) {
            LoggingUtils.logError("RES_MESSAGE_ERROR", "处理普通消息响应失败", e);
        }
    }

    /**
     * 处理拒绝消息
     * @param update 更新对象
     */
    public void resRefuseMessage(Update update){
        try {
            String userId = String.valueOf(update.getCallbackQuery().getFrom().getId());
            LoggingUtils.logSecurityEvent("BUTTON_ACCESS_DENIED", userId, "拒绝访问按钮操作");
            
            String Answer = "@"+update.getCallbackQuery().getFrom().getUserName()+" Sorry, this button can only be used by the rouser of the robot";
            SendMessage message = SendMessage // Create a message object
                    .builder()
                    .chatId(update.getCallbackQuery().getMessage().getChatId())
                    .text(Answer)
                    .build();
            try {
                // 使用ApplicationContext在需要时获取MyAmazingBot的bean
                Object bot = applicationContext.getBean("myAmazingBot");
                bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
                LoggingUtils.logOperation("REFUSE_MESSAGE", userId, "已发送拒绝消息");
            } catch (Exception e) {
                LoggingUtils.logError("BOT_REPLY_ERROR", "发送拒绝消息失败", e);
            }
        } catch (Exception e) {
            LoggingUtils.logError("REFUSE_MESSAGE_ERROR", "处理拒绝消息失败", e);
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
            LoggingUtils.logError("JSON_PARSE_ERROR", "解析JSON失败: " + jsonResponse, e);
            
            // 尝试手动解析JSON
            try {
                return parseJsonManually(jsonResponse);
            } catch (Exception manualEx) {
                LoggingUtils.logError("MANUAL_JSON_PARSE_ERROR", "手动解析JSON也失败: " + jsonResponse, manualEx);
                
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


}
