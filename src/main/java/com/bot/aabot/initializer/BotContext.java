package com.bot.aabot.initializer;

import com.bot.aabot.config.BotConfig;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * ClassName: BotContext
 * Package: com.bot.aabot.context
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/3/24
 */
@Data
@Component
public class BotContext implements InitializingBean {
    
    private final BotConfig botConfig;
    
    public static Long CreateId;
    public static int MaxContext;
    public static int ConversationTimeout;
    public static String OneOrEveryday;
    
    public BotContext(BotConfig botConfig) {
        this.botConfig = botConfig;
    }
    
    @Override
    public void afterPropertiesSet() {
        CreateId = botConfig.getCreateId();
        MaxContext = botConfig.getMessage().getMax_context();
        ConversationTimeout = botConfig.getMessage().getConversation_timeout();
        OneOrEveryday = botConfig.getOneOrEveryday();
    }
}
