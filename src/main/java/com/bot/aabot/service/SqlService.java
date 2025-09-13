package com.bot.aabot.service;

import com.bot.aabot.config.BotConfig;
import com.bot.aabot.context.DataContext;
import com.bot.aabot.context.MessageContext;
import com.bot.aabot.dao.GroupDao;
import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.GuideMessage;
import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.entity.UpLogEntity;
import com.bot.aabot.utils.LoggingUtils;
import com.bot.aabot.utils.SQLiteUtil;
import com.bot.aabot.utils.BotReplyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.springframework.scheduling.annotation.Async;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ClassName: SqlService
 * Package: com.bot.aabot.service
 * Description: **此类已经废弃**
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

    //注入重试和熔断器服务
    @Autowired
    private RetryService retryService;
    @Autowired
    private CircuitBreakerService circuitBreakerService;
    @Autowired
    private BotConfig botConfig;
    @Autowired
    private com.bot.aabot.dao.ScoreDao scoreDao;
    @Autowired
    private GroupDao groupDao;

    /**
     * 保存消息（带重试和熔断器）
     */
    public void saveMessage(Update update) {
        long startTime = System.currentTimeMillis();
        try {
            Message message = update.getMessage();
            String sql = "INSERT INTO " + DataContext.tableName + " (form_name, message_id, user_id, user_name, message_type, message, send_time, chat_id, topic_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, datetime(? , 'unixepoch', 'localtime'), ?, ?)";
            UpLogEntity upLogEntity = UpLogEntity.builder().build();
            upLogEntity.setMessageId(message.getMessageId());
            upLogEntity.setUserId(message.getFrom().getId());
            upLogEntity.setUserName(message.getFrom().getUserName());
            upLogEntity.setFormName(update.getMessage().getChat().getTitle());
            upLogEntity.setSendTime(message.getDate());
            upLogEntity.setChatId(message.getChatId());
            upLogEntity.setTopicId(message.getMessageThreadId() != null ? message.getMessageThreadId() : null);

            if (message.hasPhoto()) {
                upLogEntity.setMessageType("photo");
                String f_id = update.getMessage().getPhoto().stream().max(Comparator.comparing(PhotoSize::getFileSize))
                        .map(PhotoSize::getFileId)
                        .orElse("");
                upLogEntity.setMessage(update.getMessage().getCaption() + "[" + f_id + "]");
            } else if (message.hasText()) {
                boolean isAit = false;

                if (isGroupEnabledForResponse(update)) {
                    // 检查用户是否@了机器人
                    if (message.getEntities() != null && !message.getEntities().isEmpty()) {
                        for (var entity : message.getEntities()) {
                            if ("mention".equals(entity.getType())) {
                                String mention = message.getText().substring(entity.getOffset(), entity.getOffset() + entity.getLength());
                                // 如果提及的是当前机器人的用户名
                                if (mention.startsWith("@") && mention.substring(1).equals(botName)) {
                                    // 去除@botName
                                    String text = message.getText().replace(mention, "").trim();
                                    isAit = true;
                                    // 使用重试机制处理@消息
                                    TextMessageEntity textMessageEntity = TextMessageEntity.builder()
                                            .sessionId(String.valueOf(update.getMessage().getChatId()))
                                            .messageId(update.getMessage().getMessageId())
                                            .content(text)
                                            .sendTime(String.valueOf(update.getMessage().getDate()))
                                            .isQuestion(gptService.isQuestion(update.getMessage().getText()))
                                            .update(update)
                                            .build();
                                    aitMessageWithRetry(textMessageEntity);
                                }
                            }
                        }
                    }

                    // 新增：检测是否为回复机器人消息的场景并进行智能追问处理
                    if (!isAit) {
                        try {
                            Message replyTo = message.getReplyToMessage();
                            boolean isReplyToBot = replyTo != null && replyTo.getFrom() != null && Boolean.TRUE.equals(replyTo.getFrom().getIsBot());
                            if (isReplyToBot && replyTo != null && replyTo.hasText()) {
                                String botMsg = replyTo.getText();
                                String userMsg = message.getText();
                                // 构建供判定的字符串：机器人[...],用户[...]
                                String judgeText = "机器人[" + botMsg + "],用户[" + userMsg + "]";
                                boolean shouldReply = gptService.isQuoteQuestion(judgeText);
                                if (shouldReply) {
                                    // 将上一条bot消息纳入上下文，并触发模块化AI回复
                                    String composed = "机器人上一条消息:\n" + botMsg + "\n\n用户问题:\n" + userMsg + "\n\n请基于上述机器人消息作为背景进行解答。";
                                    TextMessageEntity textMessageEntity = TextMessageEntity.builder()
                                            .sessionId(String.valueOf(update.getMessage().getChatId()))
                                            .messageId(update.getMessage().getMessageId())
                                            .content(composed)
                                            .sendTime(String.valueOf(update.getMessage().getDate()))
                                            .isQuestion(true)
                                            .update(update)
                                            .build();
                                    // 复用统一的@消息AI处理链路（结构化输出 + 上下文）
                                    aitMessageWithRetry(textMessageEntity);
                                    isAit = true;
                                } else {
                                    localSaveMessage(update);
                                }
                            } else {
                                // 非回复机器人消息，按原流程落地保存
                                localSaveMessage(update);
                            }
                        } catch (Exception ex) {
                            // 发生异常时回退到本地保存，保证不丢消息
                            localSaveMessage(update);
                        }
                    }
                }
                upLogEntity.setMessageType("text");
                upLogEntity.setMessage(message.getText());
            } else if (message.hasVideo()) {
                upLogEntity.setMessageType("video");
                upLogEntity.setMessage(message.getVideo().getFileId());
            } else if (message.hasAnimation()) {
                upLogEntity.setMessageType("animation");
                upLogEntity.setMessage(message.getAnimation().getFileId());
            } else if (message.hasDocument()) {
                upLogEntity.setMessageType("document");
                upLogEntity.setMessage(message.getDocument().getFileName() + "[" + message.getDocument().getFileId() + "]");
            }
            if (upLogEntity.getMessageType() == null) {
                upLogEntity.setMessageType("other");
                upLogEntity.setMessage("other");
            }

            // 使用重试和熔断器保存消息到数据库
            saveMessageWithRetryAndCircuitBreaker(sql, upLogEntity);

            LoggingUtils.logPerformance("saveMessage", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("SAVE_MESSAGE_ERROR", "保存消息失败", e);
        }
    }

    /**
     * 带重试和熔断器的数据库保存操作
     */
    private void saveMessageWithRetryAndCircuitBreaker(String sql, UpLogEntity upLogEntity) {
        retryService.executeWithRetryAndCircuitBreaker(() -> {
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

                    LoggingUtils.logOperation("SAVE_MESSAGE", String.valueOf(upLogEntity.getUserId()), "保存消息成功");
                    return null;
                }, "DATABASE_SAVE", circuitBreakerService)
                .exceptionally(throwable -> {
                    LoggingUtils.logError("SAVE_MESSAGE_FINAL_FAILURE", "数据库保存最终失败", (Exception) throwable);
                    return null;
                });
    }

    /**
     * 带重试的@消息处理
     */
    private void aitMessageWithRetry(TextMessageEntity textMessageEntity) {
        retryService.executeWithRetryAndCircuitBreaker(() -> {
                    aitMessage(textMessageEntity);
                    return null;
                }, "AIT_MESSAGE_PROCESS", circuitBreakerService)
                .exceptionally(throwable -> {
                    LoggingUtils.logError("AIT_MESSAGE_RETRY_FAILED", "@消息处理重试失败", (Exception) throwable);
                    return null;
                });
    }

    /**
     * 处理回调查询（异步 + 重试）
     *
     * @param update 更新对象
     */
    @Async
    public void callbackQuery(Update update) {
        if ("click the button below to learn more:".equals(update.getCallbackQuery().getData())) {
            return;
        }
        long startTime = System.currentTimeMillis();

        // 优先处理广告管控相关的按钮
        try {
            String data = update.getCallbackQuery().getData();
            if (data != null && (data.startsWith("SPAM_UNRESTRICT:") || data.startsWith("SPAM_BLACKLIST:"))) {
                String[] parts = data.split(":");
                if (parts.length >= 3) {
                    String chatIdStr = parts[1];
                    String userIdStr = parts[2];
                    Object bot = applicationContext.getBean("tgBot");
                    if (data.startsWith("SPAM_UNRESTRICT:")) {
                        adminUnrestrictUser(bot, chatIdStr, userIdStr);
                        sendAdminResult("已解除封禁", chatIdStr, userIdStr);
                    } else {
                        adminBlacklistUser(bot, chatIdStr, userIdStr);
                        sendAdminResult("已加入黑名单并移出群聊", chatIdStr, userIdStr);
                    }
                }
                LoggingUtils.logPerformance("callbackQuery_spam", startTime);
                return;
            }
        } catch (Exception e) {
            LoggingUtils.logError("SPAM_CALLBACK_ERROR", "处理广告管控回调失败", e);
            return;
        }

        // 使用重试和熔断器处理其他回调查询
        retryService.executeWithRetryAndCircuitBreaker(() -> {
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
                        if (update.getCallbackQuery().getFrom().getId().equals(senderId)) {
                            callbackMessage(update, messageId);
                        } else {
//                    // 如果回复消息发送者ID与当前用户ID不一致，则发送拒绝回答的信息
//                    resRefuseMessage(update);
                        }
                        LoggingUtils.logPerformance("callbackQuery", startTime);
                        return null;
                    } catch (Exception e) {
                        LoggingUtils.logError("CALLBACK_ERROR", "处理回调查询失败", e);
                        throw new RuntimeException(e); // 重新抛出以触发重试
                    }
                }, "CALLBACK_QUERY_PROCESS", circuitBreakerService)
                .exceptionally(throwable -> {
                    LoggingUtils.logError("CALLBACK_QUERY_FINAL_FAILURE", "回调查询处理最终失败", (Exception) throwable);
                    return null;
                });
    }

    // 本地保存消息
    public void localSaveMessage(Update update) {
        try {
            String threadId = null;
            if (update.getMessage().getReplyToMessage() != null && update.getMessage().getReplyToMessage().getMessageThreadId() != null) {
                threadId = String.valueOf(update.getMessage().getReplyToMessage().getMessageThreadId());
            } else if (update.getMessage().getMessageThreadId() != null && update.getMessage().getReplyToMessage() == null) {
                // 兼容新版Telegram Bot API，SendMessage有setMessageThreadId
                threadId = String.valueOf(update.getMessage().getMessageThreadId());
            }

            String sessionId = update.getMessage().getChatId() + "_" + threadId;
            TextMessageEntity textMessageEntity = TextMessageEntity.builder()
                    .sessionId(sessionId)
                    .messageId(update.getMessage().getMessageId())
                    .content(update.getMessage().getText())
                    .sendTime(String.valueOf(update.getMessage().getDate()))
                    .isQuestion(gptService.isQuestion(update.getMessage().getText()))
                    .update(update)
                    .build();


            // 使用新的线程安全队列方法
            boolean success = MessageContext.offerMessage(sessionId, textMessageEntity);
            if (success) {
                LoggingUtils.logOperation("LOCAL_SAVE", String.valueOf(update.getMessage().getFrom().getId()),
                        String.format("本地保存消息成功，队列大小: %d", MessageContext.getQueueSize()));
            } else {
                LoggingUtils.logError("LOCAL_SAVE_QUEUE_FULL",
                        String.format("消息队列已满，消息丢弃 - 用户: %s, 内容: %s",
                                update.getMessage().getFrom().getId(),
                                update.getMessage().getText().substring(0, Math.min(50, update.getMessage().getText().length()))), null);
            }
        } catch (Exception e) {
            LoggingUtils.logError("LOCAL_SAVE_ERROR", "本地保存消息失败", e);
        }
    }

    public void editMessage(Update update) {
        try {
            Integer messageId = update.getEditedMessage().getMessageId();
            String sql = "UPDATE " + DataContext.tableName + " SET is_edit = 1 WHERE message_id =" + messageId;
            jdbcTemplate.update(sql);
            LoggingUtils.logOperation("EDIT_MESSAGE", String.valueOf(update.getEditedMessage().getFrom().getId()), "更新消息编辑状态成功");
        } catch (Exception e) {
            LoggingUtils.logError("EDIT_MESSAGE_ERROR", "更新消息编辑状态失败", e);
        }
    }

    public void callbackMessage(Update update, Integer messageId) {
        long startTime = System.currentTimeMillis();
        try {
            // 检查AI互动功能是否开启
            if (!botConfig.isAiInteraction()) {
                LoggingUtils.logOperation("AI_INTERACTION_DISABLED", "system", "AI互动功能已关闭，跳过回调查询处理");
                return;
            }

            String user = update.getCallbackQuery().getMessage().getChatId() + "_" + update.getCallbackQuery().getFrom().getId();
            GuideMessage guideMessage = gptService.answerUserQuestionWithAit(user, update.getCallbackQuery().getData());
            // 构建使用GuideMessage结构的回复
            SendMessage message = SendMessage
                    .builder()
                    .chatId(update.getCallbackQuery().getMessage().getChatId())
                    .replyToMessageId(messageId)
//                    .text(update.getCallbackQuery().getData()+" : "+guideMessage.getReply() + "\n\n"+"click the button below to learn more:"+"\n"+"A:"+guideMessage.getGuide1()+"\n"+"B:"+guideMessage.getGuide2())
                    .text(update.getCallbackQuery().getData() + " : " + guideMessage.getReply() + "\n\n" + "点击下方按钮了解更多信息:" + "\n" + "A:" + guideMessage.getGuide1() + "\n" + "B:" + guideMessage.getGuide2())
                    .replyMarkup(InlineKeyboardMarkup
                            .builder()
//                            .keyboardRow(new InlineKeyboardRow(
//                                    InlineKeyboardButton
//                                            .builder()
//                                            .text("click the button below to learn more:")
////                                                .callbackData(guideMessage.getGuide1())
//                                            .callbackData("click the button below to learn more:")
//                                            .build()
//                            ))
                            .keyboardRow(new InlineKeyboardRow(
                                    InlineKeyboardButton
                                            .builder()
                                            .text("A")
                                            .callbackData(guideMessage.getGuide1())
                                            .build(),
                                    InlineKeyboardButton
                                            .builder()
                                            .text("B")
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
            String sql = "INSERT INTO " + DataContext.resTableName + " (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                    + "VALUES (?, ?, ?, ?, ?,?)";
            // 将GuideMessage转换为JSON字符串用于数据库存储
            String gptResponse = String.format("{\"reply\":\"%s\",\"guide1\":\"%s\",\"guide2\":\"%s\",\"guide3\":\"%s\"}",
                    guideMessage.getReply(), guideMessage.getGuide1(), guideMessage.getGuide2(), guideMessage.getGuide3());
            jdbcTemplate.update(sql, update.getCallbackQuery().getData(), update.getCallbackQuery().getMessage().getMessageId(), update.getCallbackQuery().getFrom().getUserName(), update.getCallbackQuery().getFrom().getId(), gptResponse, user);
            try {
                BotReplyUtil.reply(message, update);
                LoggingUtils.logOperation("CALLBACK_MESSAGE", String.valueOf(update.getCallbackQuery().getFrom().getId()), "处理回调查询成功");
            } catch (Exception e) {
                LoggingUtils.logError("BOT_REPLY_ERROR", "机器人回复消息失败", e);
            }
            LoggingUtils.logPerformance("callbackMessage", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("CALLBACK_MESSAGE_ERROR", "处理回调查询失败", e);
        }
    }

    public void aitMessage(TextMessageEntity textMessageEntity) {
        long startTime = System.currentTimeMillis();
        try {
            // 检查AI互动功能是否开启
            if (!botConfig.isAiInteraction()) {
                LoggingUtils.logOperation("AI_INTERACTION_DISABLED",
                        textMessageEntity.getSessionId() + "_" + textMessageEntity.getUpdate().getMessage().getFrom().getId(),
                        "AI互动功能已关闭，跳过@回复处理");
                return;
            }

            String user = textMessageEntity.getSessionId() + "_" + textMessageEntity.getUpdate().getMessage().getFrom().getId();
            LoggingUtils.logBusinessOperation("AI_RESPONSE", user, "开始处理@回复: " + textMessageEntity.getContent());

            GuideMessage guideMessage = gptService.answerUserQuestionWithAit(user, textMessageEntity.getContent());
            if (guideMessage.getReply() != null && !guideMessage.getReply().isEmpty()) {
                // 构建使用GuideMessage结构的回复
                SendMessage message = SendMessage
                        .builder()
                        .chatId(textMessageEntity.getSessionId())
                        .replyToMessageId(textMessageEntity.getMessageId())
                        .text(guideMessage.getReply() + "\n\n" + "click the button below to learn more:" + "\n" + "A:" + guideMessage.getGuide1() + "\n" + "B:" + guideMessage.getGuide2())
                        .replyMarkup(InlineKeyboardMarkup
                                .builder()
//                            .keyboardRow(new InlineKeyboardRow(
//                                    InlineKeyboardButton
//                                            .builder()
//                                            .text("click the button below to learn more:")
////                                                .callbackData(guideMessage.getGuide1())
//                                            .callbackData("click the button below to learn more:")
//                                            .build()
//                            ))
                                .keyboardRow(new InlineKeyboardRow(
                                                InlineKeyboardButton
                                                        .builder()
                                                        .text("A")
                                                        .callbackData(guideMessage.getGuide1())
                                                        .build(),
                                                InlineKeyboardButton
                                                        .builder()
                                                        .text("B")
                                                        .callbackData(guideMessage.getGuide2())
                                                        .build()
                                        )
                                )
                                .build())
                        .build();
                String sql = "INSERT INTO " + DataContext.resTableName + " (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                        + "VALUES (?, ?, ?, ?, ?,?)";
                // 将GuideMessage转换为JSON字符串用于数据库存储
                String gptResponse = String.format("{\"reply\":\"%s\",\"guide1\":\"%s\",\"guide2\":\"%s\",\"guide3\":\"%s\"}",
                        guideMessage.getReply(), guideMessage.getGuide1(), guideMessage.getGuide2(), guideMessage.getGuide3());
                jdbcTemplate.update(sql, textMessageEntity.getUpdate().getMessage().getText(), textMessageEntity.getMessageId(), textMessageEntity.getUpdate().getMessage().getFrom().getUserName(), textMessageEntity.getUpdate().getMessage().getFrom().getId(), gptResponse, user);
                LoggingUtils.logOperation("DB_OPERATION", user, "保存AI回复到数据库");

                try {
                    BotReplyUtil.reply(message, textMessageEntity.getUpdate());
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

    public void directResMessage(TextMessageEntity textMessageEntity) {
        if ("click the button below to learn more:".equals(textMessageEntity.getContent())) {
            return;
        }
        long startTime = System.currentTimeMillis();
        try {
            // 检查AI互动功能是否开启
            if (!botConfig.isAiInteraction()) {
                LoggingUtils.logOperation("AI_INTERACTION_DISABLED",
                        textMessageEntity.getSessionId() + "_" + textMessageEntity.getUpdate().getMessage().getFrom().getId(),
                        "AI互动功能已关闭，跳过直接回复处理");
                return;
            }

            String user = textMessageEntity.getSessionId() + "_" + textMessageEntity.getUpdate().getMessage().getFrom().getId();
            LoggingUtils.logBusinessOperation("DIRECT_RESPONSE", user, "开始处理直接回复: " + textMessageEntity.getContent());

            GPTAnswer gptAnswer = gptService.answerUserQuestion(user, textMessageEntity.getContent());
            SendMessage message = SendMessage // Create a message object
                    .builder()
                    .chatId(textMessageEntity.getSessionId())
                    .replyToMessageId(textMessageEntity.getMessageId())
                    .text(gptAnswer.getAnswer())
                    .build();
            String sql = "INSERT INTO " + DataContext.resTableName + " (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                    + "VALUES (?, ?, ?, ?, ?,?)";
            jdbcTemplate.update(sql, textMessageEntity.getUpdate().getMessage().getText(), textMessageEntity.getMessageId(), textMessageEntity.getUpdate().getMessage().getFrom().getUserName(), textMessageEntity.getUpdate().getMessage().getFrom().getId(), gptAnswer.getAnswer(), gptAnswer.getSessionId());
            LoggingUtils.logOperation("DB_OPERATION", user, "保存直接回复到数据库");

            try {
                BotReplyUtil.reply(message, textMessageEntity.getUpdate());
                LoggingUtils.logOperation("SEND_REPLY", user, "成功发送直接回复消息");
            } catch (Exception e) {
                LoggingUtils.logError("BOT_REPLY_ERROR", "机器人直接回复消息失败", e);
            }
            LoggingUtils.logPerformance("directResMessage", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("DIRECT_MESSAGE_ERROR", "处理直接回复消息失败", e);
        }
    }

    public void resMessage(Update update) {
        long startTime = System.currentTimeMillis();
        try {
            // 检查AI互动功能是否开启
            if (!botConfig.isAiInteraction()) {
                LoggingUtils.logOperation("AI_INTERACTION_DISABLED",
                        update.getMessage().getChatId() + "_" + update.getMessage().getFrom().getId(),
                        "AI互动功能已关闭，跳过自动回复处理");
                return;
            }

            if (gptService.isQuestion(update.getMessage().getText())) {
                // 构建user为群聊id加用户id
                String user = update.getMessage().getChatId() + "_" + update.getMessage().getFrom().getId();
                LoggingUtils.logBusinessOperation("AUTO_RESPONSE", user, "开始处理自动回复: " + update.getMessage().getText());

                GPTAnswer gptAnswer = gptService.answerUserQuestion(user, update.getMessage().getText());
                SendMessage message = SendMessage // Create a message object
                        .builder()
                        .chatId(update.getMessage().getChatId())
                        .replyToMessageId(update.getMessage().getMessageId())
                        .text(gptAnswer.getAnswer())
                        .build();
                String sql = "INSERT INTO " + DataContext.resTableName + " (original_question, message_id, user_name, user_id, gpt_res,session_id) "
                        + "VALUES (?, ?, ?, ?, ?,?)";
                jdbcTemplate.update(sql, update.getMessage().getText(), update.getMessage().getMessageId(), update.getMessage().getFrom().getUserName(), update.getMessage().getFrom().getId(), gptAnswer.getAnswer(), gptAnswer.getSessionId());
                LoggingUtils.logOperation("DB_OPERATION", user, "保存自动回复到数据库");

                try {
                    BotReplyUtil.reply(message, update);
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
     *
     * @param update 更新对象
     */
    public void resRefuseMessage(Update update) {
        try {
            String userId = String.valueOf(update.getCallbackQuery().getFrom().getId());
            LoggingUtils.logSecurityEvent("BUTTON_ACCESS_DENIED", userId, "拒绝访问按钮操作");

            String Answer = "@" + update.getCallbackQuery().getFrom().getUserName() + " Sorry, this button can only be used by the rouser of the robot";
            SendMessage message = SendMessage // Create a message object
                    .builder()
                    .chatId(update.getCallbackQuery().getMessage().getChatId())
                    .text(Answer)
                    .build();
            try {
                BotReplyUtil.reply(message, update);
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
     *
     * @param jsonResponse GPT回答的JSON字符串
     * @return GuideMessage对象
     */
    @SuppressWarnings("unused")
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
                        .reply("Failed to parse response: " + jsonResponse)
                        .guide1("Try rephrasing your question")
                        .guide2("Ask a more specific question")
                        .guide3("Provide more context")
                        .build();
            }
        }
    }

    /**
     * 手动解析JSON字符串为GuideMessage对象
     *
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
     *
     * @param jsonStr   JSON字符串
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

    /**
     * 检查群聊是否在 res_group 表中，允许bot回复
     *
     * @param update 消息更新
     * @return 是否允许回复
     */
    private boolean isGroupEnabledForResponse(Update update) {
        try {
            String groupId = String.valueOf(update.getMessage().getChatId());
            String threadId = null;

            // 获取thread_id
            if (update.getMessage().getReplyToMessage() != null &&
                    update.getMessage().getReplyToMessage().getMessageThreadId() != null) {
                threadId = String.valueOf(update.getMessage().getReplyToMessage().getMessageThreadId());
            } else if (update.getMessage().getMessageThreadId() != null &&
                    update.getMessage().getReplyToMessage() == null) {
                threadId = String.valueOf(update.getMessage().getMessageThreadId());
            }

            // 查询数据库，检查群聊是否在表中
            String sql;
            Object[] params;

            if (threadId != null) {
                // 如果有thread_id，检查精确匹配
                sql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id = ? ";
                params = new Object[]{groupId, threadId};
            } else {
                // 如果没有thread_id，检查group_id匹配且thread_id为空的记录
                sql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id IS NULL";
                params = new Object[]{groupId};
            }

            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, params);
            boolean isEnabled = count != null && count > 0;

            LoggingUtils.logOperation("CHECK_GROUP_RESPONSE", groupId,
                    String.format("检查群聊回复权限 - GroupId: %s, ThreadId: %s, 结果: %s",
                            groupId, threadId, isEnabled ? "允许" : "禁止"));

            return isEnabled;

        } catch (Exception e) {
            LoggingUtils.logError("CHECK_GROUP_RESPONSE_ERROR", "检查群聊回复权限失败", e);
            return false; // 默认不允许回复
        }
    }

    /**
     * 添加群聊到回复白名单
     *
     * @param groupId  群聊ID
     * @param threadId 话题ID（可为空）
     * @return 操作结果消息
     */
    public String addGroupToResponse(String groupId, String threadId) {
        try {

            // 检查是否已存在
            String checkSql;
            Object[] checkParams;

            if (threadId != null) {
                checkSql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id = ?";
                checkParams = new Object[]{groupId, threadId};
            } else {
                checkSql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id IS NULL";
                checkParams = new Object[]{groupId};
            }

            Integer existingCount = jdbcTemplate.queryForObject(checkSql, Integer.class, checkParams);
            if (existingCount != null && existingCount > 0) {
                return String.format("群聊已在回复白名单中\nGroupId: %s\nThreadId: %s",
                        groupId, threadId != null ? threadId : "无");
            }

            // 插入新记录
            String insertSql = "INSERT INTO res_group (group_id, thread_id) VALUES (?, ?)";
            jdbcTemplate.update(insertSql, groupId, threadId);

            LoggingUtils.logOperation("ADD_GROUP_RESPONSE", groupId,
                    String.format("添加群聊到回复白名单 - GroupId: %s, ThreadId: %s", groupId, threadId));

            return String.format("✅ 成功添加群聊到回复白名单\nGroupId: %s\nThreadId: %s\n\n现在机器人会在此群聊中回复消息",
                    groupId, threadId != null ? threadId : "无");

        } catch (Exception e) {
            LoggingUtils.logError("ADD_GROUP_RESPONSE_ERROR", "添加群聊到回复白名单失败", e);
            return "❌ 添加失败：" + e.getMessage();
        }
    }

    /**
     * 从回复白名单移除群聊
     *
     * @param groupId  群聊ID
     * @param threadId 话题ID（可为空）
     * @return 操作结果消息
     */
    public String removeGroupFromResponse(String groupId, String threadId) {
        try {
            String deleteSql;
            Object[] deleteParams;

            if (threadId != null) {
                deleteSql = "DELETE FROM res_group WHERE group_id = ? AND thread_id = ?";
                deleteParams = new Object[]{groupId, threadId};
            } else {
                deleteSql = "DELETE FROM res_group WHERE group_id = ? AND thread_id IS NULL";
                deleteParams = new Object[]{groupId};
            }

            int deletedRows = jdbcTemplate.update(deleteSql, deleteParams);

            if (deletedRows > 0) {
                LoggingUtils.logOperation("REMOVE_GROUP_RESPONSE", groupId,
                        String.format("从回复白名单移除群聊 - GroupId: %s, ThreadId: %s", groupId, threadId));

                return String.format("✅ 成功从回复白名单移除群聊\nGroupId: %s\nThreadId: %s\n\n机器人将不再在此群聊中回复消息",
                        groupId, threadId != null ? threadId : "无");
            } else {
                return String.format("⚠️ 群聊不在回复白名单中\nGroupId: %s\nThreadId: %s",
                        groupId, threadId != null ? threadId : "无");
            }

        } catch (Exception e) {
            LoggingUtils.logError("REMOVE_GROUP_RESPONSE_ERROR", "从回复白名单移除群聊失败", e);
            return "❌ 移除失败：" + e.getMessage();
        }
    }

    /**
     * 获取当前群聊的回复白名单状态
     *
     * @param groupId  群聊ID
     * @param threadId 话题ID（可为空）
     * @return 状态消息
     */
    public String getGroupResponseStatus(String charName, String groupId, String threadId) {
        try {
            String checkSql;
            Object[] checkParams;

            if (threadId != null) {
                checkSql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id = ?";
                checkParams = new Object[]{groupId, threadId};
            } else {
                checkSql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id IS NULL";
                checkParams = new Object[]{groupId};
            }

            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, checkParams);
            boolean isEnabled = count != null && count > 0;

            String status = isEnabled ? "✅ 已启用" : "❌ 未启用";
            return String.format("当前群聊回复状态：%s\nGroupId: %s\nThreadId: %s\n\n%s",
                    status, groupId, threadId != null ? threadId : "无",
                    isEnabled ? "机器人会回复消息" : "机器人不会回复消息");

        } catch (Exception e) {
            LoggingUtils.logError("GET_GROUP_RESPONSE_STATUS_ERROR", "获取群聊回复状态失败", e);
            return "❌ 获取状态失败：" + e.getMessage();
        }
    }

    /**
     * 广告消息过滤检查
     *
     * @param update 更新对象
     * @return true表示是广告消息需要处理，false表示正常消息
     */
    public boolean checkAndHandleSpamMessage(Update update) {
        try {
            // 只处理文本消息
            if (!update.hasMessage() || !update.getMessage().hasText()) {
                return false;
            }

            Message message = update.getMessage();
            String messageText = message.getText();
            Long chatId = message.getChatId();
            Long userId = message.getFrom().getId();
            Integer messageId = message.getMessageId();

            // 获取并清理消息内容
            String cleanedText = cleanMessageText(messageText);

            // 读取禁止词文件
            List<String> forbiddenWords = readForbiddenWords();

            // 检查是否包含禁止内容
            for (String forbiddenWord : forbiddenWords) {
                if (cleanedText.contains(forbiddenWord.trim())) {
                    LoggingUtils.logSecurityEvent("SPAM_DETECTED", String.valueOf(userId),
                            String.format("检测到广告消息 - 违规内容: %s", forbiddenWord));

                    // 处理违规消息：撤回、封禁、通知管理员
                    handleSpamMessage(applicationContext.getBean("tgBot"), update, forbiddenWord);
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            LoggingUtils.logError("SPAM_CHECK_ERROR", "广告检查失败", e);
            return false;
        }
    }

    /**
     * 清理消息文本（去除空格、标点符号等）
     */
    private String cleanMessageText(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\s,。;、]", "").toLowerCase();
    }

    /**
     * 读取禁止词文件
     */
    private List<String> readForbiddenWords() {
        List<String> words = new ArrayList<>();
        String forbidFilePath = botConfig.getForbidUrl();

        if (forbidFilePath == null || forbidFilePath.trim().isEmpty()) {
            LoggingUtils.logError("FORBID_FILE_ERROR", "禁止词文件路径未配置", null);
            return words;
        }

        try {
            Path path = Paths.get(forbidFilePath);
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        words.add(cleanMessageText(trimmed));
                    }
                }
            } else {
                LoggingUtils.logError("FORBID_FILE_NOT_FOUND", "禁止词文件不存在: " + forbidFilePath, null);
            }
        } catch (Exception e) {
            LoggingUtils.logError("READ_FORBID_FILE_ERROR", "读取禁止词文件失败", e);
        }

        return words;
    }

    /**
     * 处理垃圾消息
     */
    private void handleSpamMessage(Object bot, Update update, String violationContent) {
        Message message = update.getMessage();
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        Integer messageId = message.getMessageId();

        // 撤回消息
        deleteMessage(bot, chatId, messageId);

        // 限制用户权限 1小时 (3600秒)
        banUser(bot, chatId, userId, 3600);

        // 通知管理员
        notifyAdmin(bot, update, violationContent);

        LoggingUtils.logOperation("SPAM_HANDLED", String.valueOf(userId),
                "已处理广告消息: 撤回消息并限制用户权限1小时");
    }

    /**
     * 撤回消息
     */
    private void deleteMessage(Object bot, Long chatId, Integer messageId) {
        try {
            DeleteMessage deleteMessage = DeleteMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .build();

            // 使用bot的deleteMessage方法来执行删除操作
            bot.getClass().getMethod("deleteMessage", DeleteMessage.class).invoke(bot, deleteMessage);

        } catch (Exception e) {
            LoggingUtils.logError("DELETE_MESSAGE_ERROR", "撤回消息失败", e);
        }
    }

    /**
     * 限制用户权限（临时禁言）
     */
    private void banUser(Object bot, Long chatId, Long userId, int duration) {
        try {
            // 使用RestrictChatMember来限制用户权限，而不是踢出群聊
            // 限制所有权限（禁言）
            ChatPermissions restrictedPermissions = ChatPermissions.builder()
                    .canSendMessages(false)
                    .canSendAudios(false)
                    .canSendDocuments(false)
                    .canSendPhotos(false)
                    .canSendVideos(false)
                    .canSendVideoNotes(false)
                    .canSendVoiceNotes(false)
                    .canSendPolls(false)
                    .canSendOtherMessages(false)
                    .canAddWebPagePreviews(false)
                    .canChangeInfo(false)
                    .canInviteUsers(false)
                    .canPinMessages(false)
                    .canManageTopics(false)
                    .build();

            RestrictChatMember restrictChatMember = RestrictChatMember.builder()
                    .chatId(String.valueOf(chatId))
                    .userId(userId)
                    .permissions(restrictedPermissions)
                    .untilDate((int) (System.currentTimeMillis() / 1000 + duration))
                    .build();

            // 使用bot的restrictUser方法来执行限制操作
            bot.getClass().getMethod("restrictUser", RestrictChatMember.class).invoke(bot, restrictChatMember);

        } catch (Exception e) {
            LoggingUtils.logError("RESTRICT_USER_ERROR", "限制用户权限失败", e);
        }
    }

    /**
     * 通知管理员
     */
    private void notifyAdmin(Object bot, Update update, String violationContent) {
        try {
            String adminGroupId = groupDao.getAdminGroup();
            if (adminGroupId == null) {
                LoggingUtils.logError("ADMIN_GROUP_NOT_SET", "管理员群组未设置，无法发送通知", null);
                return;
            }

            // 获取基本信息
            Message message = update.getMessage();
            LoggingUtils.logOperation(
                    "SPAM_NOTIFY_CONTEXT",
                    String.valueOf(message.getFrom().getId()),
                    String.format("chatId=%s, msgId=%s", message.getChatId(), message.getMessageId())
            );

            // 获取群聊信息
            String chatTitle = message.getChat().getTitle();
            if (chatTitle == null || chatTitle.trim().isEmpty()) {
                chatTitle = "未知群聊";
            }

            // 获取话题信息
            String topicInfo = "";
            if (message.getMessageThreadId() != null) {
                topicInfo = String.format(" (话题ID: %d)", message.getMessageThreadId());
//                topicInfo = String.format(" (话题ID: %s)", message.getForwardSenderName());
            }

            // 获取用户信息
            String userName = getUserDisplayName(message.getFrom());

            // 获取消息内容（限制长度）
            String messageText = message.getText();
            if (messageText != null && messageText.length() > 100) {
                messageText = messageText.substring(0, 100) + "...";
            }
            if (messageText == null) {
                messageText = "[无文本内容]";
            }

            // 构建通知消息
            String notificationText = String.format(
                    "🚨 检测到广告消息\n\n" +
                            "📍 群组: %s%s\n" +
                            "👤 用户: %s (ID: %s)\n" +
                            "📝 消息内容: %s\n\n" +
                            "✅ 已自动处理：\n" +
                            "• 撤回消息\n" +
                            "• 限制用户权限1小时",
                    chatTitle, topicInfo, userName, String.valueOf(message.getFrom().getId()), messageText
            );

            // 构建管理员操作按钮
            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text("解除封禁")
                                    .callbackData(String.format("SPAM_UNRESTRICT:%s:%s", String.valueOf(message.getChatId()), String.valueOf(message.getFrom().getId())))
                                    .build(),
                            InlineKeyboardButton.builder()
                                    .text("加入黑名单")
                                    .callbackData(String.format("SPAM_BLACKLIST:%s:%s", String.valueOf(message.getChatId()), String.valueOf(message.getFrom().getId())))
                                    .build()
                    ))
                    .build();

            // 使用现有的SendMessage方式
            SendMessage sendMessage = SendMessage.builder()
                    .chatId(adminGroupId)
                    .text(notificationText)
                    .replyMarkup(keyboard)
                    .build();

            // 发送通知消息
            bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, sendMessage);

            LoggingUtils.logOperation("ADMIN_NOTIFIED", adminGroupId, "已通知管理员群组（含操作按钮）");

        } catch (Exception e) {
            LoggingUtils.logError("NOTIFY_ADMIN_ERROR", "通知管理员失败", e);
        }
    }

    /**
     * 向管理员群回送执行结果
     */
    private void sendAdminResult(String action, String chatIdStr, String userIdStr) {
        try {
            String adminGroupId = groupDao.getAdminGroup();
            if (adminGroupId == null) return;
            String text = String.format("✅ %s\nGroupId: %s\nUserId: %s", action, chatIdStr, userIdStr);
            SendMessage msg = SendMessage.builder()
                    .chatId(adminGroupId)
                    .text(text)
                    .build();
            Object bot = applicationContext.getBean("tgBot");
            bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, msg);
        } catch (Exception e) {
            LoggingUtils.logError("ADMIN_RESULT_NOTIFY_ERROR", "管理员结果通知失败", e);
        }
    }

    /**
     * 管理员操作：解除封禁（恢复权限）
     */
    private void adminUnrestrictUser(Object bot, String chatIdStr, String userIdStr) {
        try {
            long userId = Long.parseLong(userIdStr);
            // 恢复用户基本发言权限
            ChatPermissions allowPermissions = ChatPermissions.builder()
                    .canSendMessages(true)
                    .canSendAudios(true)
                    .canSendDocuments(true)
                    .canSendPhotos(true)
                    .canSendVideos(true)
                    .canSendVideoNotes(true)
                    .canSendVoiceNotes(true)
                    .canSendPolls(true)
                    .canSendOtherMessages(true)
                    .canAddWebPagePreviews(true)
                    .build();
            RestrictChatMember unrestrict = RestrictChatMember.builder()
                    .chatId(chatIdStr)
                    .userId(userId)
                    .permissions(allowPermissions)
                    .build();
            bot.getClass().getMethod("restrictUser", RestrictChatMember.class).invoke(bot, unrestrict);
            LoggingUtils.logOperation("UNRESTRICT_DONE", userIdStr, "已解除封禁并恢复权限");
        } catch (Exception e) {
            LoggingUtils.logError("UNRESTRICT_ERROR", "解除封禁失败", e);
        }
    }

    /**
     * 管理员操作：加入黑名单（撤回历史消息 + 永久封禁并踢出）
     */
    private void adminBlacklistUser(Object bot, String chatIdStr, String userIdStr) {
        try {
            long userId = Long.parseLong(userIdStr);
            // 1) 批量撤回历史消息
            try {
                List<Integer> messageIds = jdbcTemplate.query(
                        "SELECT message_id FROM " + DataContext.tableName + " WHERE chat_id = ? AND user_id = ?",
                        ps -> {
                            ps.setString(1, chatIdStr);
                            ps.setString(2, userIdStr);
                        },
                        (rs, rowNum) -> rs.getInt("message_id")
                );
                if (messageIds != null) {
                    LoggingUtils.logOperation("BLACKLIST_DELETE_COUNT", userIdStr, "准备撤回消息数量=" + messageIds.size());
                    for (Integer mid : messageIds) {
                        try {
                            deleteMessage(bot, Long.parseLong(chatIdStr), mid);
                        } catch (Exception ex) {
                            // 忽略单条失败
                        }
                    }
                }
            } catch (Exception e) {
                LoggingUtils.logError("BLACKLIST_DELETE_HISTORY_ERROR", "删除历史消息失败", e);
            }
            // 2) 永久封禁并踢出
            try {
                BanChatMember ban = BanChatMember.builder()
                        .chatId(chatIdStr)
                        .userId(userId)
                        .untilDate((int) (System.currentTimeMillis() / 1000 + 365 * 24 * 3600))
                        .build();
                bot.getClass().getMethod("banUser", BanChatMember.class).invoke(bot, ban);
                LoggingUtils.logOperation("BLACKLIST_DONE", userIdStr, "已加入黑名单并踢出群聊");
            } catch (Exception e) {
                LoggingUtils.logError("BLACKLIST_BAN_ERROR", "加入黑名单/踢出失败", e);
            }
        } catch (Exception e) {
            LoggingUtils.logError("BLACKLIST_ERROR", "处理黑名单失败", e);
        }
    }

    /**
     * 获取用户显示名称
     */
    private String getUserDisplayName(org.telegram.telegrambots.meta.api.objects.User user) {
        StringBuilder displayName = new StringBuilder();

        // 优先使用用户名
        if (user.getUserName() != null && !user.getUserName().trim().isEmpty()) {
            displayName.append("@").append(user.getUserName());
        } else {
            // 如果没有用户名，使用真实姓名
            if (user.getFirstName() != null && !user.getFirstName().trim().isEmpty()) {
                displayName.append(user.getFirstName());
            }
            if (user.getLastName() != null && !user.getLastName().trim().isEmpty()) {
                if (displayName.length() > 0) {
                    displayName.append(" ");
                }
                displayName.append(user.getLastName());
            }
        }

        // 如果都没有，使用默认值
        if (displayName.length() == 0) {
            displayName.append("未知用户");
        }

        return displayName.toString();
    }


}
