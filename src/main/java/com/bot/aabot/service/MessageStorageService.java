package com.bot.aabot.service;

import com.bot.aabot.config.BotConfig;
import com.bot.aabot.context.DataContext;
import com.bot.aabot.context.MessageContext;
import com.bot.aabot.dao.GroupDao;
import com.bot.aabot.dao.MessageDao;
import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.entity.UpLogEntity;
import com.bot.aabot.utils.BotReplyUtil;
import com.bot.aabot.utils.LoggingUtils;
import com.bot.aabot.utils.MessagesUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.Comparator;
import java.util.List;

/**
 * ClassName: MessageStorageService
 * Package: com.bot.aabot.service
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/9/4
 */
@Slf4j
@Service
public class MessageStorageService {
    @Autowired
    private ApplicationContext applicationContext;
    @Value("${bot.name}")
    String botName;
    @Autowired
    private UserService userService;
    @Autowired
    private GroupManagementService groupManagementService;
    @Autowired
    private RetryService retryService;
    @Autowired
    private CircuitBreakerService circuitBreakerService;
    @Autowired
    private BotConfig botConfig;
    @Autowired
    private GPTService gptService;
    @Autowired
    private AIResponseService aiResponseService;
    @Autowired
    private MessageDao messageDao;
    @Autowired
    private GroupDao groupDao;


    /**
     * 广告消息过滤检查
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
            String cleanedText = MessagesUtil.cleanMessageText(messageText);

            // 读取禁止词文件
            List<String> forbiddenWords = MessagesUtil.readForbiddenWords(botConfig.getForbidUrl());

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
     * 保存消息（带重试和熔断器）
     */
    public void saveMessage(Update update) {
        long startTime = System.currentTimeMillis();
        try {
            Message message = update.getMessage();
            UpLogEntity upLogEntity = UpLogEntity.builder()
                    .messageId(message.getMessageId())
                    .userId(message.getFrom().getId())
                    .userName(message.getFrom().getUserName())
                    .formName(update.getMessage().getChat().getTitle())
                    .sendTime(message.getDate())
                    .chatId(message.getChatId())
                    .topicId(message.getMessageThreadId() != null ? message.getMessageThreadId() : null)
                    .build();

            if(message.hasPhoto()){
                upLogEntity.setMessageType("photo");
                String f_id = update.getMessage().getPhoto().stream().max(Comparator.comparing(PhotoSize::getFileSize))
                        .map(PhotoSize::getFileId)
                        .orElse("");
                upLogEntity.setMessage(update.getMessage().getCaption()+"["+f_id+"]");
            }else if(message.hasText()){
                ordinaryMessageProcessing(update);
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

            // 使用重试和熔断器保存消息到数据库
            saveMessageWithRetryAndCircuitBreaker(upLogEntity);

            LoggingUtils.logPerformance("saveMessage", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("SAVE_MESSAGE_ERROR", "保存消息失败", e);
        }
    }

    /**
     * 处理`编辑消息`事件
     * @param update
     */
    public void editMessage(Update update){
        try {
            messageDao.editMessage(update);
            LoggingUtils.logOperation("EDIT_MESSAGE", String.valueOf(update.getEditedMessage().getFrom().getId()), "更新消息编辑状态成功");
        } catch (Exception e) {
            LoggingUtils.logError("EDIT_MESSAGE_ERROR", "更新消息编辑状态失败", e);
        }
    }

    /**
     *  对于普通消息要进行特殊的处理。
     * @param update
     */
    private void ordinaryMessageProcessing(Update update) {
        boolean isAit = false;
        Message message = update.getMessage();
        if (groupManagementService.isGroupEnabledForResponse(update)) {

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

            // 检测是否为回复机器人消息的场景并进行智能追问处理
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
                        } else {
                            // 将消息加入到线程队列。
                            localSaveMessage(update);
                        }
                    } else {
                        // 将消息加入到线程队列。
                        localSaveMessage(update);
                    }
                } catch (Exception ex) {
                    // 发生异常时回退到本地保存，保证不丢消息
                    localSaveMessage(update);
                }
            }
        }
    }

    /**
     * 带重试和背压的@消息处理
     * @param textMessageEntity
     */
    private void aitMessageWithRetry(TextMessageEntity textMessageEntity) {
        retryService.executeWithRetryAndCircuitBreaker(() -> {
                    aiResponseService.aitMessage(textMessageEntity);
                    return null;
                }, "AIT_MESSAGE_PROCESS", circuitBreakerService)
                .exceptionally(throwable -> {
                    LoggingUtils.logError("AIT_MESSAGE_RETRY_FAILED", "@消息处理重试失败", (Exception) throwable);
                    return null;
                });
    }


    /**
     * 带重试和熔断器的数据库保存操作
     * @param upLogEntity
     */
    private void saveMessageWithRetryAndCircuitBreaker(UpLogEntity upLogEntity) {
        retryService.executeWithRetryAndCircuitBreaker(() -> {
                    messageDao.saveMessages(upLogEntity);
                    LoggingUtils.logOperation("SAVE_MESSAGE", String.valueOf(upLogEntity.getUserId()), "保存消息成功");
                    return null;
                }, "DATABASE_SAVE", circuitBreakerService)
                .exceptionally(throwable -> {
                    LoggingUtils.logError("SAVE_MESSAGE_FINAL_FAILURE", "数据库保存最终失败", (Exception) throwable);
                    return null;
                });
    }

    /**
     * 将消息保存到消息队列
     * @param update
     */
    public void localSaveMessage(Update update) {
        try {
            // 获取线程 ID 并构造会话 ID。
            String threadId = BotReplyUtil.getThreadId(update);

            String sessionId = update.getMessage().getChatId() + "_" + threadId;

            TextMessageEntity textMessageEntity = TextMessageEntity.builder()
                    .sessionId(sessionId)
                    .messageId(update.getMessage().getMessageId())
                    .content(update.getMessage().getText())
                    .sendTime(String.valueOf(update.getMessage().getDate()))
                    .isQuestion(gptService.isQuestion(update.getMessage().getText()))
                    .update(update)
                    .build();


            // 线程安全队列方法
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

    /**
     * 处理违禁消息
     * @param bot
     * @param update
     * @param violationContent
     */
    private void handleSpamMessage(Object bot, Update update, String violationContent) {
        Message message = update.getMessage();
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        Integer messageId = message.getMessageId();

        // 撤回消息
        MessagesUtil.deleteMessage(bot, chatId, messageId);

        // 限制用户权限 1小时 (3600秒)
        MessagesUtil.banUser(bot, chatId, userId, 3600);

        // 通知管理员
        notifyAdmin(bot, update, violationContent);

        LoggingUtils.logOperation("SPAM_HANDLED", String.valueOf(userId),
                "已处理广告消息: 撤回消息并限制用户权限1小时");
    }

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
            }

            // 获取用户信息
            String userName = userService.getUserDisplayName(message.getFrom());

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


}
