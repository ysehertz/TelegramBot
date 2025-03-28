package com.bot.aabot.initializer;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
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
    
    @Value("${bot.oneOrEveryday}")
    private String oneOrEveryday;
    @Value("${bot.message.conversation_timeout}")
    private int conversation_timeout;
    @Value("${bot.message.max_context}")
    private int max_context;
    @Value("${bot.createId}")
    private Long create_id;
    public static Long CreateId;
    public static int MaxContext;
    public static int ConversationTimeout;
    public static String OneOrEveryday;
    
    @Override
    public void afterPropertiesSet() {
        CreateId = create_id;
        MaxContext = max_context;
        ConversationTimeout = conversation_timeout;
        OneOrEveryday = oneOrEveryday;
    }
}
