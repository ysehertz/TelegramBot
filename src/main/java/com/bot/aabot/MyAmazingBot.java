package com.bot.aabot;

import com.bot.aabot.service.GPTService;
import com.bot.aabot.service.SqlService;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Comparator;
import java.util.List;

@Component
public class MyAmazingBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {
    @Autowired
    GPTService gptService;
    @Autowired
    SqlService sqlService;
    private final TelegramClient telegramClient;

    public MyAmazingBot() {
        telegramClient = new OkHttpTelegramClient(getBotToken());
    }

    @Override
    public String getBotToken() {
        return "7647087531:AAEgk9kpws5RXS0pQg_iauLR1TT75JVjHXU";
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }


    @Override
    public void consume(Update update) {
        if(update.hasMessage()){
            sqlService.saveMessage(update);
        }else if(update.hasEditedMessage()){
            sqlService.editMessage(update);
        }
        System.out.println(update.getChatMember());
        if(update.hasMessage() && update.getMessage().hasText()){
            SendMessage sendMessage;
            if( (sendMessage = sqlService.resMessage(update)) != null) {
                try {
                    telegramClient.execute(sendMessage);
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
            }
        }
//        // We check if the update has a message and the message has text
//        if (update.hasMessage() && update.getMessage().hasText()) {
//
//            // Set variables
//            String message_text = update.getMessage().getText();
//            long chat_id = update.getMessage().getChatId();
//
//            SendMessage message = SendMessage // Create a message object
//                    .builder()
//                    .chatId(chat_id)
//                    .replyToMessageId(update.getMessage().getMessageId())
//                    .text(message_text)
//                    .build();
//            try {
//                telegramClient.execute(message); // Sending our message object to user
//            } catch (TelegramApiException e) {
//                e.printStackTrace();
//            }
//        }

//        if (update.hasMessage() && update.getMessage().hasText()) {
//            System.out.println(update.getMessage().getChat().getTitle());
//            System.out.println(update.getMessage().getChatId());
//            System.out.println(update.getMessage().getMessageId());
//            System.out.println(update.getMessage().getText());
//        }else  if(update.hasDeletedBusinessMessage()){
//            System.out.println(update.getDeletedBusinessMessages().getBusinessConnectionId());
//            System.out.println(update.getDeletedBusinessMessages().getMessageIds());
//        }else if(update.hasEditedMessage()) {
//            System.out.println(update.getEditedMessage().getChatId());
//            System.out.println(update.getEditedMessage().getMessageId());
//            System.out.println(update.getEditedMessage().getText());
//        } else if (update.getMessage().hasPhoto()) {
//            System.out.println(update.getMessage().getPhoto());
//            List<PhotoSize> photos = update.getMessage().getPhoto();
//            String f_id = photos.stream().max(Comparator.comparing(PhotoSize::getFileSize))
//                    .map(PhotoSize::getFileId)
//                    .orElse("");
//            System.out.println(f_id);
//        }else if(update.getMessage().hasVideo()){
//            System.out.println(update.getMessage().getVideo());
//            System.out.println(update.getMessage().getVideo().getFileId());
//        }else if(update.getMessage().hasAnimation()){
//            System.out.println(update.getMessage().getAnimation());
//            System.out.println(update.getMessage().getAnimation().getFileId());
//        }else if(update.getMessage().hasDocument()) {
//            System.out.println("拿到了文件");
//            System.out.println(update.getMessage().getDocument().getFileName());
//            System.out.println(update.getMessage().getDocument());
//            System.out.println(update.getMessage().getDocument().getFileId());
//        }
    }
}