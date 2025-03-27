package com.bot.aabot.config;

import com.bot.aabot.MyAmazingBot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Configuration
public class TelegramBotConfig {
    private static final String BOT_TOKEN = "7647087531:AAEgk9kpws5RXS0pQg_iauLR1TT75JVjHXU";

    @Autowired
    private MyAmazingBot myAmazingBot;

    @Bean
    public TelegramBotsLongPollingApplication telegramBotsLongPollingApplication() throws TelegramApiException {
        TelegramBotsLongPollingApplication application = new TelegramBotsLongPollingApplication();
        myAmazingBot.onRegister();
        application.registerBot(BOT_TOKEN, myAmazingBot);
        return application;
    }
} 