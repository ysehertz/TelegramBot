package com.bot.aabot.service;

import com.bot.aabot.config.BotConfig;
import com.bot.aabot.dao.MessageDao;
import com.bot.aabot.entity.GuideMessage;
import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.utils.BotReplyUtil;
import com.bot.aabot.utils.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * ClassName: AIResponseService
 * Package: com.bot.aabot.service
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/9/4
 */
@Slf4j
@Service
public class AIResponseService {

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    GPTService gptService;
    @Autowired
    MessageDao messageDao;
    @Autowired
    private BotConfig botConfig;



    /**
     * 对@bot触发的提问进行处理
     * @param textMessageEntity
     */
    public void aitMessage(TextMessageEntity textMessageEntity){
        long startTime = System.currentTimeMillis();
        try {
            // 检查AI互动功能是否开启
            if (!botConfig.isAiInteraction()) {
                LoggingUtils.logOperation("AI_INTERACTION_DISABLED",
                        textMessageEntity.getSessionId()+"_"+textMessageEntity.getUpdate().getMessage().getFrom().getId(),
                        "AI互动功能已关闭，跳过@回复处理");
                return;
            }

            String user = textMessageEntity.getSessionId()+"_"+textMessageEntity.getUpdate().getMessage().getFrom().getId();
            LoggingUtils.logBusinessOperation("AI_RESPONSE", user, "开始处理@回复: " + textMessageEntity.getContent());

            GuideMessage guideMessage = gptService.answerUserQuestionWithAit(user,textMessageEntity.getContent());
            if(guideMessage.getReply() != null && !guideMessage.getReply().isEmpty()){
                // 构建使用GuideMessage结构的回复
                SendMessage message = SendMessage
                        .builder()
                        .chatId(textMessageEntity.getSessionId())
                        .replyToMessageId(textMessageEntity.getMessageId())
                        .text(guideMessage.getReply()+ "\n\n"+"点击下面的按钮了解更多信息:"+"\n"+"A:"+guideMessage.getGuide1()+"\n"+"B:"+guideMessage.getGuide2())
                        .replyMarkup(InlineKeyboardMarkup
                                .builder()
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

                messageDao.saveReply(guideMessage , textMessageEntity.getUpdate() ,user);

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

    /**
     * 对回调查询触发的提问进行处理
     * @param update
     * @param messageId
     */
    public void callbackMessage(Update update,Integer messageId){
        long startTime = System.currentTimeMillis();
        try {
            // 检查AI互动功能是否开启
            if (!botConfig.isAiInteraction()) {
                LoggingUtils.logOperation("AI_INTERACTION_DISABLED", "system", "AI互动功能已关闭，跳过回调查询处理");
                return;
            }

            String user = update.getCallbackQuery().getMessage().getChatId()+"_"+update.getCallbackQuery().getFrom().getId();
            GuideMessage guideMessage = gptService.answerUserQuestionWithAit(user,update.getCallbackQuery().getData());
            // 构建使用GuideMessage结构的回复
            SendMessage message = SendMessage
                    .builder()
                    .chatId(update.getCallbackQuery().getMessage().getChatId())
                    .replyToMessageId(messageId)
                    .text(update.getCallbackQuery().getData()+" : "+guideMessage.getReply() + "\n\n"+"点击下方按钮了解更多信息:"+"\n"+"A:"+guideMessage.getGuide1()+"\n"+"B:"+guideMessage.getGuide2())
                    .replyMarkup(InlineKeyboardMarkup
                            .builder()
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
                            .build())
                    .build();

            messageDao.saveReply(guideMessage, update,user);

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

}
