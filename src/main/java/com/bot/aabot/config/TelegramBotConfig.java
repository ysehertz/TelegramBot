package com.bot.aabot.config;

import com.bot.aabot.MyAmazingBot;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

@Configuration
public class TelegramBotConfig {
    private static final String BOT_TOKEN = "7647087531:AAEgk9kpws5RXS0pQg_iauLR1TT75JVjHXU";

    // 使用ApplicationContext代替直接依赖
    private final ApplicationContext applicationContext;

    public TelegramBotConfig(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Bean
    public TelegramBotsLongPollingApplication telegramBotsLongPollingApplication() throws TelegramApiException {
        TelegramBotsLongPollingApplication application = new TelegramBotsLongPollingApplication();
        
        // 通过ApplicationContext获取MyAmazingBot实例
        MyAmazingBot myAmazingBot = applicationContext.getBean(MyAmazingBot.class);
        myAmazingBot.onRegister();

        DefaultGetUpdatesGenerator defaultGetUpdatesGenerator = new DefaultGetUpdatesGenerator(List.of("message_reaction","message"));

        application.registerBot(BOT_TOKEN,() -> TelegramUrl.DEFAULT_URL, defaultGetUpdatesGenerator,myAmazingBot);
        
        return application;
    }
} 