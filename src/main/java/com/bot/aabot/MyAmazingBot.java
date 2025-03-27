package com.bot.aabot;

import com.bot.aabot.initializer.BotContext;
import com.bot.aabot.service.GPTService;
import com.bot.aabot.service.SqlService;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.abilitybots.api.bot.AbilityBot;
import org.telegram.telegrambots.abilitybots.api.db.DBContext;
import org.telegram.telegrambots.abilitybots.api.objects.Ability;
import org.telegram.telegrambots.abilitybots.api.objects.Locality;
import org.telegram.telegrambots.abilitybots.api.objects.Privacy;
import org.telegram.telegrambots.abilitybots.api.toggle.AbilityToggle;
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

import static org.telegram.telegrambots.abilitybots.api.objects.Locality.ALL;
import static org.telegram.telegrambots.abilitybots.api.objects.Privacy.PUBLIC;

@Component
public class MyAmazingBot extends AbilityBot{
    @Autowired
    GPTService gptService;
    @Autowired
    SqlService sqlService;

    protected MyAmazingBot() {
        super(new OkHttpTelegramClient("7647087531:AAEgk9kpws5RXS0pQg_iauLR1TT75JVjHXU"), "Tgbot");
    }


    @Override
    public long creatorId() {
        return BotContext.CreateId;
    }

    @Override
    public void consume(Update update) {
        if(update.hasMessage()){
            sqlService.saveMessage(update);
        }else if(update.hasEditedMessage()){
            sqlService.editMessage(update);
        }
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
        super.consume(update);
    }
    public Ability sayHelloWorld() {
        return Ability
                .builder()
                .name("hello")
                .info("says hello world!")
                .locality(ALL)
                .privacy(PUBLIC)
                .action(ctx -> silent.send("Hello world!", ctx.chatId()))
                .build();
    }

    public Ability startAbility() {
        return Ability
                .builder()
                .name("start")
                .info("开始使用机器人")
                .locality(Locality.ALL)
                .privacy(Privacy.PUBLIC)
                .action((ctx) -> this.silent.send(String.join("\n", "欢迎使用机器人！", "我可以帮你回答各种问题。", "直接发送你的问题即可。"), ctx.chatId()))
                .build();
    }

    public Ability helpAbility() {
        return Ability
                .builder()
                .name("help")
                .info("显示帮助信息")
                .locality(Locality.ALL)
                .privacy(Privacy.PUBLIC)
                .action((ctx) -> this.silent.send(String.join("\n", "机器人使用帮助：", "1. 直接发送问题，我会尽力回答", "2. 使用 /start 开始使用", "3. 使用 /help 显示此帮助信息"), ctx.chatId()))
                .build();
    }
}