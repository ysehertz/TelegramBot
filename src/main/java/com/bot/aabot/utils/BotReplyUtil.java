package com.bot.aabot.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

@Slf4j
@Component
public class BotReplyUtil implements ApplicationContextAware {
    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    /**
     * 统一回复方法，自动判断并设置threadId（话题id）
     */
    public static void reply(SendMessage message, Update update) {
        try {
            Message msg = null;
            if (update.getMessage() != null) {
                msg = update.getMessage();
            } else if (update.getEditedMessage() != null) {
                msg = update.getEditedMessage();
            } else if (update.getCallbackQuery() != null && update.getCallbackQuery().getMessage() != null) {
                MaybeInaccessibleMessage maybeMsg = update.getCallbackQuery().getMessage();
                if (maybeMsg instanceof Message) {
                    msg = (Message) maybeMsg;
                }
            }
            if (msg != null && msg.getMessageThreadId() != null && message.getReplyToMessageId() == null) {
                // 兼容新版Telegram Bot API，SendMessage有setMessageThreadId
                message.setMessageThreadId(msg.getMessageThreadId());
            }
            Object bot = context.getBean("myAmazingBot");
            bot.getClass().getMethod("replyMessage", SendMessage.class).invoke(bot, message);
            LoggingUtils.logOperation("BOT_REPLY", String.valueOf(msg != null ? msg.getFrom().getId() : "unknown"), "发送消息成功");
        } catch (Exception e) {
            log.error("[BOT_REPLY_ERROR] 发送消息失败", e);
            LoggingUtils.logError("BOT_REPLY_ERROR", "发送消息失败", e);
        }
    }
} 