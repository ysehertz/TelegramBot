package com.bot.aabot;

import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.initializer.BotContext;
import com.bot.aabot.service.GPTService;
import com.bot.aabot.service.SqlService;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import lombok.extern.slf4j.Slf4j;
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
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bot.aabot.service.ConfigManagementService;
import org.telegram.telegrambots.abilitybots.api.objects.MessageContext;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.telegram.telegrambots.abilitybots.api.objects.Locality.ALL;
import static org.telegram.telegrambots.abilitybots.api.objects.Privacy.PUBLIC;

@Slf4j
@Component
public class MyAmazingBot extends AbilityBot{
    @Autowired
    private GPTService gptService;
    @Autowired
    private SqlService sqlService;
    @Autowired
    private ConfigManagementService configManagementService;
    @Autowired
    private ObjectMapper objectMapper;

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
        }else if(update.hasCallbackQuery()){
            sqlService.callbackQuery(update);
        }
        super.consume(update);
    }

    // 回复消息
    public void replyMessage(SendMessage message) {
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
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
                .action((ctx) -> this.silent.send(String.join("\n", 
                        "机器人使用帮助：", 
                        "1. 直接发送问题，我会尽力回答", 
                        "2. 使用 /start 开始使用", 
                        "3. 使用 /help 显示此帮助信息",
                        "4. 使用 /clearc 清除所有用户会话记录（仅管理员可用）"), ctx.chatId()))
                .build();
    }

    public Ability clearConversationsAbility() {
        return Ability
                .builder()
                .name("clearc")
                .info("清除所有用户的会话记录")
                .locality(Locality.ALL)
                .privacy(Privacy.CREATOR) // 只有创建者可以使用此命令
                .action((ctx) -> {
                    int count = gptService.clearAllConversations();
                    this.silent.send(String.format("已成功清除所有用户会话记录，共 %d 个会话。", count), ctx.chatId());
                })
                .build();
    }

    public Ability showConfig() {
        return Ability
                .builder()
                .name("showconfig")
                .info("显示当前配置")
                .locality(ALL)
                .privacy(PUBLIC)
                .action((MessageContext ctx) -> {
                    try {
                        Map<String, Object> config = configManagementService.getCurrentConfig();
                        String configJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
                        silent.send("当前配置：\n" + configJson, ctx.chatId());
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                        silent.send("获取配置失败：" + e.getMessage(), ctx.chatId());
                    }
                })
                .build();
    }

    public Ability updateConfig() {
        return Ability
                .builder()
                .name("updateconfig")
                .info("更新配置")
                .locality(ALL)
                .privacy(PUBLIC)
                .action((MessageContext ctx) -> {
                    try {
                        String[] args = ctx.arguments();
                        if (args.length < 2) {
                            silent.send("用法：/updateconfig <配置路径> <新值>\n例如：/updateconfig message.conversation_timeout 30", ctx.chatId());
                            return;
                        }

                        String path = args[0];
                        String value = args[1];
                        
                        // 将路径转换为Map结构
                        Map<String, Object> updates = createUpdateMap(path, value);
                        
                        // 更新配置
                        configManagementService.updateConfig(updates);
                        
                        silent.send("配置已更新", ctx.chatId());
                    } catch (Exception e) {
                        log.error("更新配置时出错:{}", e.getMessage());
                        silent.send("更新配置失败" , ctx.chatId());
                    }
                })
                .build();
    }

    private Map<String, Object> createUpdateMap(String path, String value) {
        String[] parts = path.split("\\.");
        Map<String, Object> result = Map.of();
        
        for (int i = parts.length - 1; i >= 0; i--) {
            if (i == parts.length - 1) {
                // 尝试将值转换为适当的类型
                Object typedValue = convertValue(value);
                result = Map.of(parts[i], typedValue);
            } else {
                result = Map.of(parts[i], result);
            }
        }
        
        return result;
    }

    private Object convertValue(String value) {
        // 尝试转换为数字
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // 尝试转换为布尔值
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return Boolean.parseBoolean(value);
            }
            // 如果不是特殊类型，返回字符串
            return value;
        }
    }
}