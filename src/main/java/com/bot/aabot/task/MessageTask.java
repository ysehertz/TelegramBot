package com.bot.aabot.task;

import com.bot.aabot.MyAmazingBot;
import com.bot.aabot.service.GPTService;
import com.bot.aabot.service.SqlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bot.aabot.context.MessageContext;
import com.bot.aabot.entity.TextMessageEntity;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * ClassName: MessageTask
 * Package: com.bot.aabot.task
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/1
 */
@Component
@Slf4j
@EnableScheduling
public class MessageTask {
    @Autowired
    MyAmazingBot myAmazingBot;
    @Autowired
    GPTService gptService;
    @Autowired
    SqlService sqlService;

    @Scheduled(cron = "0 0/1 * * * ?")
    public void messageTask() {
        // 从MessageContext.messageContextList中的第一条开始检查，如果当前时间与消息发送时间相差30分钟，则将消息取出并其他处理
        // 如果不为空
        while (!(MessageContext.messageContextList.size() == 0)){
            // 获取当前时间(以秒为单位的时间戳)
            long currentTime = System.currentTimeMillis() / 1000;
            // 获取消息发送时间
            long sendTime = Long.parseLong(MessageContext.messageContextList.get(0).getSendTime());
            // 如果当前时间与消息发送时间相差30分钟
            if (currentTime - sendTime >=  30 * 60 ) {
                if(MessageContext.messageContextList.get(0).isQuestion()&&!(gptService.isBeAnswered())){
                    sqlService.directResMessage(MessageContext.messageContextList.get(0));
//                    myAmazingBot.replyMessage(sendMessage);
                }
                MessageContext.messageContextList.remove(0);
            }else{
                break;
            }
        }
//            for (TextMessageEntity textMessageEntity : MessageContext.messageContextList) {
//                // 获取当前时间(以秒为单位的时间戳)
//                long currentTime = System.currentTimeMillis() / 1000;
//                // 获取消息发送时间
//                long sendTime = Long.parseLong(textMessageEntity.getSendTime());
//                // 如果当前时间与消息发送时间相差30分钟
//                if (currentTime - sendTime >=  1 * 60 ) {
//                    if(textMessageEntity.isQuestion()&&!(gptService.isBeAnswered())){
//                        SendMessage sendMessage = sqlService.directResMessage(textMessageEntity);
//                        myAmazingBot.replyMessage(sendMessage);
//                    }
//                    MessageContext.messageContextList.remove(textMessageEntity);
//                }else{
//                    break;
//                }
//            }
    }
}
