package com.bot.aabot.service;

import com.bot.aabot.dao.GroupDao;
import com.bot.aabot.utils.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;

/**
 * ClassName: CallbackQueryService
 * Package: com.bot.aabot.service
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/9/4
 */
@Slf4j
@Service
public class CallbackQueryService {
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    JdbcTemplate jdbcTemplate;

    // 新增：注入重试和熔断器服务
    @Autowired
    private RetryService retryService;
    @Autowired
    private CircuitBreakerService circuitBreakerService;
    @Autowired
    private GroupDao groupDao;
    @Autowired
    private AIResponseService aiResponseService;
    @Autowired
    private UserService userService;
    /**
     * 处理回调查询（异步 + 重试）
     * @param update 更新对象
     */
    @Async
    public void callbackQuery(Update update) {

        long startTime = System.currentTimeMillis();

        // 处理广告管控相关的按钮
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
                        userService.adminBlacklistUser(bot, chatIdStr, userIdStr);
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
                        if (update.getCallbackQuery().getFrom().getId().equals(senderId)){
                            aiResponseService.callbackMessage(update,messageId);
                        }else {
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


    /**
     * 解封用户
     * @param bot
     * @param chatIdStr
     * @param userIdStr
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
     * 发送管理员结果通知
     * @param action
     * @param chatIdStr
     * @param userIdStr
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
}
