package com.bot.aabot;

import com.bot.aabot.context.ConstructionEventContext;
import com.bot.aabot.initializer.BotContext;
import com.bot.aabot.service.*;
import com.bot.aabot.utils.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.abilitybots.api.bot.AbilityBot;
import org.telegram.telegrambots.abilitybots.api.objects.Ability;
import org.telegram.telegrambots.abilitybots.api.objects.Locality;
import org.telegram.telegrambots.abilitybots.api.objects.MessageContext;
import org.telegram.telegrambots.abilitybots.api.objects.Privacy;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.telegram.telegrambots.abilitybots.api.objects.Locality.ALL;
import static org.telegram.telegrambots.abilitybots.api.objects.Privacy.PUBLIC;

import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;

@Slf4j
@Component
public class TgBot extends AbilityBot{

    @Autowired
    private CallbackQueryService callbackQueryService;
    @Autowired
    private MessageStorageService messageStorageService;
    @Autowired
    private ScoreService scoreService;
    @Autowired
    private ConfigManagementService configManagementService;
    @Autowired
    private GroupManagementService groupManagementService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private com.bot.aabot.config.BotConfig botConfig;


    // 并发处理配置
    
    @Value("${bot.concurrency.message-timeout:30000}")
    private long messageTimeout;

    protected TgBot() {
        super(new OkHttpTelegramClient("7647087531:AAEgk9kpws5RXS0pQg_iauLR1TT75JVjHXU"), "Tgbot");
    }



    @Override
    public long creatorId() {
        return BotContext.CreateId;
    }

    @Override
    public void consume(Update update) {
        long startTime = System.currentTimeMillis();
        String userId = update.hasMessage() ? String.valueOf(update.getMessage().getFrom().getId()) : "unknown";

        try {
            // 第零层：广告消息过滤（同步处理）
            if (messageStorageService.checkAndHandleSpamMessage(update)) {
                LoggingUtils.logSecurityEvent("SPAM_BLOCKED", userId, "广告消息已被拦截并处理");
                LoggingUtils.logPerformance("spam_filter", startTime);
                return; // 拦截广告消息，不继续处理
            }
            
            // 第一层：快速预处理和分发
            LoggingUtils.logOperation("MESSAGE_RECEIVED", userId, "开始处理消息更新");
            
            // 异步处理构造事件
            if (update.hasMessage() && ConstructionEventContext.creator_id != null && 
                ConstructionEventContext.creator_id.equals(String.valueOf(update.getMessage().getFrom().getId())) && 
                ConstructionEventContext.chatId != null && 
                ConstructionEventContext.chatId.equals(String.valueOf(update.getMessage().getChatId()))) {
//
                processConstructionEventAsync(update);
            }else{
                // 第二层：异步处理核心业务逻辑
                processMessageAsync(update);

                // 第三层：异步处理各种消息类型
                if (update.hasMessage()) {
                    processRegularMessageAsync(update);
                } else if (update.hasEditedMessage()) {
                    processEditedMessageAsync(update);
                } else if (update.hasCallbackQuery()) {
                    processCallbackQueryAsync(update);
                } else if (update.getMessageReaction() != null) {
                    processMessageReactionAsync(update);
                }
            }
            LoggingUtils.logPerformance("consume", startTime);
        } catch (Exception e) {
            LoggingUtils.logError("BOT_CONSUME_ERROR", "处理更新消息失败", e);
        }
    }

    /**
     * 异步处理构造事件
     */
    @Async("botAsyncExecutor")
    public void processConstructionEventAsync(Update update) {
        CompletableFuture.runAsync(() -> {
            try {
                scoreService.addEvent(update.getMessage().getText());
            } catch (Exception e) {
                LoggingUtils.logError("CONSTRUCTION_EVENT_ERROR", "处理构造事件失败", e);
            }
        }).orTimeout(messageTimeout, TimeUnit.MILLISECONDS)
        .exceptionally(throwable -> {
            LoggingUtils.logError("CONSTRUCTION_EVENT_TIMEOUT", "构造事件处理超时", (Exception) throwable);
            return null;
        });
    }

    /**
     * 异步处理核心消息逻辑
     */
    @Async("botAsyncExecutor")
    public void processMessageAsync(Update update) {
        String userId = update.hasMessage() ? String.valueOf(update.getMessage().getFrom().getId()) : "unknown";
        
        CompletableFuture.runAsync(() -> {
            try {
                // 积分相关逻辑
                scoreService.processMessage(update);
            } catch (Exception e) {
                LoggingUtils.logError("SCORE_PROCESS_ERROR", "处理积分逻辑失败", e);
            }
        }).orTimeout(messageTimeout, TimeUnit.MILLISECONDS)
        .exceptionally(throwable -> {
            LoggingUtils.logError("MESSAGE_PROCESS_TIMEOUT", "消息处理超时", (Exception) throwable);
            return null;
        });
    }

    /**
     * 异步处理普通消息
     */
    @Async("botAsyncExecutor")
    public void processRegularMessageAsync(Update update) {
        String userId = String.valueOf(update.getMessage().getFrom().getId());
        
        CompletableFuture.runAsync(() -> {
            try {
                // 消息保存
                messageStorageService.saveMessage(update);
                
                // 处理AbilityBot的消息处理
                super.consume(update);
                
                LoggingUtils.logOperation("REGULAR_MESSAGE_PROCESSED", userId, "普通消息处理完成");
            } catch (Exception e) {
                LoggingUtils.logError("REGULAR_MESSAGE_ERROR", "处理普通消息失败", e);
            }
        }).orTimeout(messageTimeout, TimeUnit.MILLISECONDS)
        .exceptionally(throwable -> {
            LoggingUtils.logError("REGULAR_MESSAGE_TIMEOUT", "普通消息处理超时", (Exception) throwable);
            return null;
        });
    }

    /**
     * 异步处理编辑消息
     */
    @Async("botAsyncExecutor")
    public void processEditedMessageAsync(Update update) {
        String userId = String.valueOf(update.getEditedMessage().getFrom().getId());
        
        CompletableFuture.runAsync(() -> {
            try {
                messageStorageService.editMessage(update);
                LoggingUtils.logOperation("EDITED_MESSAGE_PROCESSED", userId, "编辑消息处理完成");
            } catch (Exception e) {
                LoggingUtils.logError("EDITED_MESSAGE_ERROR", "处理编辑消息失败", e);
            }
        }).orTimeout(messageTimeout, TimeUnit.MILLISECONDS)
        .exceptionally(throwable -> {
            LoggingUtils.logError("EDITED_MESSAGE_TIMEOUT", "编辑消息处理超时", (Exception) throwable);
            return null;
        });
    }

    /**
     * 异步处理回调查询
     */
    @Async("botAsyncExecutor")
    public void processCallbackQueryAsync(Update update) {
        String userId = String.valueOf(update.getCallbackQuery().getFrom().getId());
        
        CompletableFuture.runAsync(() -> {
            try {
                AnswerCallbackQuery answer = new AnswerCallbackQuery(update.getCallbackQuery().getId());
                telegramClient.execute(answer);
                
                // 处理积分排名分页回调
                String callbackData = update.getCallbackQuery().getData();
                if (callbackData.startsWith("pointList_")) {
                    handlePointListCallback(update);
                }else {
                    callbackQueryService.callbackQuery(update);
                }
                LoggingUtils.logOperation("CALLBACK_QUERY_PROCESSED", userId, "回调查询处理完成");
            } catch (Exception e) {
                LoggingUtils.logError("CALLBACK_QUERY_ERROR", "处理回调查询失败", e);
            }
        }).orTimeout(messageTimeout, TimeUnit.MILLISECONDS)
        .exceptionally(throwable -> {
            LoggingUtils.logError("CALLBACK_QUERY_TIMEOUT", "回调查询处理超时", (Exception) throwable);
            return null;
        });
    }

    /**
     * 异步处理消息反应
     */
    @Async("botAsyncExecutor")
    public void processMessageReactionAsync(Update update) {
        String userId = String.valueOf(update.getMessageReaction().getUser().getId());
        
        CompletableFuture.runAsync(() -> {
            try {
                LoggingUtils.logOperation("MESSAGE_REACTION_PROCESSED", userId, "消息反应处理完成");
            } catch (Exception e) {
                LoggingUtils.logError("MESSAGE_REACTION_ERROR", "处理消息反应失败", e);
            }
        }).orTimeout(messageTimeout, TimeUnit.MILLISECONDS)
        .exceptionally(throwable -> {
            LoggingUtils.logError("MESSAGE_REACTION_TIMEOUT", "消息反应处理超时", (Exception) throwable);
            return null;
        });
    }

    // 回复消息
    public void replyMessage(SendMessage message) {
        long startTime = System.currentTimeMillis();
        try {
            telegramClient.execute(message);
            LoggingUtils.logOperation("REPLY_MESSAGE", String.valueOf(message.getChatId()), "回复消息成功");
            LoggingUtils.logPerformance("replyMessage", startTime);
        } catch (TelegramApiException e) {
            LoggingUtils.logError("REPLY_MESSAGE_ERROR", "回复消息失败", e);
        }
    }

    // 删除消息
    public void deleteMessage(DeleteMessage deleteMessage) {
        long startTime = System.currentTimeMillis();
        try {
            telegramClient.execute(deleteMessage);
            LoggingUtils.logOperation("DELETE_MESSAGE", String.valueOf(deleteMessage.getChatId()), "删除消息成功");
            LoggingUtils.logPerformance("deleteMessage", startTime);
        } catch (TelegramApiException e) {
            LoggingUtils.logError("DELETE_MESSAGE_ERROR", "删除消息失败", e);
        }
    }

    // 封禁用户
    public void banUser(BanChatMember banChatMember) {
        long startTime = System.currentTimeMillis();
        try {
            telegramClient.execute(banChatMember);
            LoggingUtils.logOperation("BAN_USER", String.valueOf(banChatMember.getChatId()), "封禁用户成功");
            LoggingUtils.logPerformance("banUser", startTime);
        } catch (TelegramApiException e) {
            LoggingUtils.logError("BAN_USER_ERROR", "封禁用户失败", e);
        }
    }

    // 限制用户权限
    public void restrictUser(RestrictChatMember restrictChatMember) {
        long startTime = System.currentTimeMillis();
        try {
            telegramClient.execute(restrictChatMember);
            LoggingUtils.logOperation("RESTRICT_USER", String.valueOf(restrictChatMember.getChatId()), "限制用户权限成功");
            LoggingUtils.logPerformance("restrictUser", startTime);
        } catch (TelegramApiException e) {
            LoggingUtils.logError("RESTRICT_USER_ERROR", "限制用户权限失败", e);
        }
    }

    /**
     * 添加活动
     * @return
     */
    public Ability addEvent() {
        return Ability
                .builder()
                .name("addevent")
                .info("添加活动")
                .locality(Locality.ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        scoreService.addEvent(ctx);
                    } catch (Exception e) {
                        LoggingUtils.logError("ADD_EVENT_ERROR", "添加活动失败", e);
                        silent.send("添加活动失败", ctx.chatId());
                    }
                })
                .build();
    }



//    public Ability startAbility() {
//        return Ability
//                .builder()
//                .name("start")
//                .info("开始使用机器人")
//                .locality(Locality.ALL)
//                .privacy(Privacy.PUBLIC)
//                .action((ctx) -> {
//                    try {
//                        this.silent.send(String.join("\n", "欢迎使用机器人！", "我可以帮你回答各种问题。", "直接发送你的问题即可。"), ctx.chatId());
//                        LoggingUtils.logOperation("START_BOT", String.valueOf(ctx.chatId()), "用户开始使用机器人");
//                    } catch (Exception e) {
//                        LoggingUtils.logError("START_BOT_ERROR", "启动机器人失败", e);
//                    }
//                })
//                .build();
//    }

//    public Ability helpAbility() {
//        return Ability
//                .builder()
//                .name("help")
//                .info("显示帮助信息")
//                .locality(Locality.ALL)
//                .privacy(Privacy.PUBLIC)
//                .action((ctx) -> {
//                    try {
//                        this.silent.send(String.join("\n",
//                                "机器人使用帮助：",
//                                "1. 直接发送问题，我会尽力回答",
//                                "2. 使用 /start 开始使用",
//                                "3. 使用 /help 显示此帮助信息",
//                                "4. 使用 /clearc 清除所有用户会话记录（仅管理员可用）"), ctx.chatId());
//                        LoggingUtils.logOperation("SHOW_HELP", String.valueOf(ctx.chatId()), "显示帮助信息");
//                    } catch (Exception e) {
//                        LoggingUtils.logError("SHOW_HELP_ERROR", "显示帮助信息失败", e);
//                    }
//                })
//                .build();
//    }

//    public Ability clearConversationsAbility() {
//        return Ability
//                .builder()
//                .name("clearc")
//                .info("清除所有用户的会话记录")
//                .locality(Locality.ALL)
//                .privacy(Privacy.CREATOR) // 只有创建者可以使用此命令
//                .action((ctx) -> {
//                    try {
//                        int count = gptService.clearAllConversations();
//                        this.silent.send(String.format("已成功清除所有用户会话记录，共 %d 个会话。", count), ctx.chatId());
//                        LoggingUtils.logOperation("CLEAR_CONVERSATIONS", String.valueOf(ctx.chatId()), "清除所有会话记录");
//                    } catch (Exception e) {
//                        LoggingUtils.logError("CLEAR_CONVERSATIONS_ERROR", "清除会话记录失败", e);
//                    }
//                })
//                .build();
//    }

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
                        LoggingUtils.logOperation("SHOW_CONFIG", String.valueOf(ctx.chatId()), "显示当前配置");
                    } catch (Exception e) {
                        LoggingUtils.logError("SHOW_CONFIG_ERROR", "显示配置失败", e);
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
                        LoggingUtils.logOperation("UPDATE_CONFIG", String.valueOf(ctx.chatId()), "更新配置成功");
                    } catch (Exception e) {
                        LoggingUtils.logError("UPDATE_CONFIG_ERROR", "更新配置失败", e);
                        silent.send("更新配置失败", ctx.chatId());
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

    /**
     * 将群聊记录到数据库中
     * @return
     */
    public Ability initChat(){
        return Ability
                .builder()
                .name("init")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx)->{
                    try {
                        scoreService.initChat(ctx);
                    } catch (Exception e) {
                        LoggingUtils.logError("INIT_CHAT_ERROR", "初始化会话失败", e);
                        silent.send("初始化会话失败", ctx.chatId());
                    }
                })
                .build();
    }

    /**
     * 设置管理员群组
     * @return
     */
    public Ability initAdminGroup(){
        return Ability
                .builder()
                .name("initadmin")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx)->{
                    try {
//                        scoreService.initChat(ctx);
                        scoreService.initAdminGroup(ctx);
                    } catch (Exception e) {
                        LoggingUtils.logError("INIT_ADMIN_GROUP_ERROR", "初始化管理群组失败", e);
                        silent.send("初始化管理群组失败", ctx.chatId());
                    }
                })
                .build();
    }

    /**
     * 添加特殊积分给用户
     * @return
     */
    public Ability addPoints() {
        return Ability
                .builder()
                .name("addpoints")
                .info("为用户添加特殊积分")
                .locality(Locality.ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        String[] args = ctx.arguments();
                        if (args.length < 2) {
                            silent.send("用法: /addpoints <数字> @用户名", ctx.chatId());
                            return;
                        }

                        // 解析积分数量
                        int points;
                        try {
                            points = Integer.parseInt(args[0]);
                        } catch (NumberFormatException e) {
                            silent.send("错误：积分数量必须是数字", ctx.chatId());
                            return;
                        }

                        // 获取用户名
                        String userName = args[1];

                        // 调用服务方法添加积分
                        String result = scoreService.addPointsToUser(
                            String.valueOf(ctx.chatId()),
                            userName,
                            points
                        );

                        silent.send(result, ctx.chatId());

                    } catch (Exception e) {
                        LoggingUtils.logError("ADD_POINTS_ERROR", "添加积分失败", e);
                        silent.send("添加积分失败", ctx.chatId());
                    }
                })
                .build();
    }


    /**
     * 普通用户查看自己的积分
     */
    public Ability viewPoints() {
        return Ability
                .builder()
                .name("viewpoint")
                .info("查看自己的积分")
                .locality(Locality.ALL)
                .privacy(PUBLIC)
                .action((ctx)->{
                    try {
                        scoreService.viewPoints(ctx);
                    } catch (Exception e) {
                        LoggingUtils.logError("VIEW_POINTS_ERROR", "查看积分失败", e);
                        silent.send("查看积分失败", ctx.chatId());
                    }

                })
                .build();
    }

    /**
     * 管理员查看活动积分排名
     */
    public Ability pointList() {
        return Ability
                .builder()
                .name("pointlist")
                .info("查看活动积分排名")
                .locality(Locality.ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        String[] args = ctx.arguments();
                        if (args.length == 0) {
                            // 显示活动列表
                            String eventListMessage = scoreService.getEventListMessage(String.valueOf(ctx.chatId()));
                            silent.send(eventListMessage, ctx.chatId());
                        } else {
                            // 显示指定活动的积分排名
                            try {
                                int eventId = Integer.parseInt(args[0]);
                                Map<String, Object> result = scoreService.getEventPointsRankingMessage(eventId, 0, String.valueOf(ctx.chatId()));

                                String text = (String) result.get("text");
                                Object keyboard = result.get("keyboard");

                                if (keyboard != null) {
                                    // 发送带键盘的消息
                                    sendMessageWithKeyboard(String.valueOf(ctx.chatId()), text, keyboard);
                                } else {
                                    // 发送普通消息
                                    silent.send(text, ctx.chatId());
                                }
                            } catch (NumberFormatException e) {
                                silent.send("活动ID必须是数字", ctx.chatId());
                            }
                        }
                    } catch (Exception e) {
                        LoggingUtils.logError("POINT_LIST_ERROR", "查看积分排名失败", e);
                        silent.send("查看积分排名失败", ctx.chatId());
                    }
                })
                .build();
    }

    /**
     * 切换AI互动功能开关
     */
    public Ability toggleAiInteraction() {
        return Ability
                .builder()
                .name("toggleai")
                .info("切换AI互动功能开关（活动提醒和回答用户问题）")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        // 获取当前AI互动状态
                        boolean currentStatus = botConfig.isAiInteraction();
                        
                        // 切换状态
                        boolean newStatus = !currentStatus;
                        
                        // 更新配置
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("aiInteraction", newStatus);
                        configManagementService.updateConfig(updates);
                        
                        // 发送确认消息
                        String statusText = newStatus ? "已开启" : "已关闭";
                        String message = String.format("AI互动功能%s\n\n当前状态：%s\n功能包括：\n• 活动提醒和总结\n• 自动回答用户问题", 
                                statusText, statusText);
                        silent.send(message, ctx.chatId());
                        
                        LoggingUtils.logOperation("TOGGLE_AI_INTERACTION", 
                                String.valueOf(ctx.user().getId()), 
                                String.format("AI互动功能切换为: %s", statusText));
                    } catch (Exception e) {
                        LoggingUtils.logError("TOGGLE_AI_INTERACTION_ERROR", "切换AI互动功能失败", e);
                        silent.send("切换AI互动功能失败", ctx.chatId());
                    }
                })
                .build();
    }

    /**
     * 查看AI互动功能状态
     */
    public Ability checkAiStatus() {
        return Ability
                .builder()
                .name("aistatus")
                .info("查看AI互动功能当前状态")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        boolean currentStatus = botConfig.isAiInteraction();
                        String statusText = currentStatus ? "已开启" : "已关闭";
                        String message = String.format("AI互动功能状态：%s\n\n功能说明：\n• 活动提醒和总结\n• 自动回答用户问题\n\n使用 /toggleai 来切换状态", statusText);
                        silent.send(message, ctx.chatId());
                    } catch (Exception e) {
                        LoggingUtils.logError("CHECK_AI_STATUS_ERROR", "查看AI互动状态失败", e);
                        silent.send("查看AI互动状态失败", ctx.chatId());
                    }
                })
                .build();
    }
    
    /**
     * 开始在当前群聊回复消息
     */
    public Ability beginResponse() {
        return Ability
                .builder()
                .name("beginres")
                .info("开始在当前群聊回复消息")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        String groupId = String.valueOf(ctx.chatId());
                        String threadId = null;
                        
                        // 获取thread_id（如果有的话）
                        if (ctx.update().getMessage().getReplyToMessage() != null && 
                            ctx.update().getMessage().getReplyToMessage().getMessageThreadId() != null) {
                            threadId = String.valueOf(ctx.update().getMessage().getReplyToMessage().getMessageThreadId());
                        } else if (ctx.update().getMessage().getMessageThreadId() != null && 
                                   ctx.update().getMessage().getReplyToMessage() == null) {
                            threadId = String.valueOf(ctx.update().getMessage().getMessageThreadId());
                        }
                        
                        String result = groupManagementService.addGroupToResponse(groupId, threadId);
                        SendMessage message = SendMessage
                                .builder()
                                .chatId(groupId)
                                .text(result)
                                .build();
                        if(threadId != null) {
                            message.setMessageThreadId(Integer.parseInt(threadId));
                        }
                        this.replyMessage(message);
//                        silent.send(result, ctx.chatId());
                        
                        LoggingUtils.logOperation("BEGIN_RESPONSE_COMMAND", 
                            String.valueOf(ctx.user().getId()),
                            String.format("管理员启用群聊回复功能 - GroupId: %s, ThreadId: %s", groupId, threadId));
                            
                    } catch (Exception e) {
                        LoggingUtils.logError("BEGIN_RESPONSE_ERROR", "启用群聊回复功能失败", e);
                        silent.send("启用群聊回复功能失败", ctx.chatId());
                    }
                })
                .build();
    }
    
    /**
     * 停止在当前群聊回复@消息
     */
    public Ability stopResponse() {
        return Ability
                .builder()
                .name("stopres")
                .info("停止在当前群聊回复消息")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        String groupId = String.valueOf(ctx.chatId());
                        String threadId = null;

                        if (ctx.update().getMessage().getReplyToMessage() != null &&
                                ctx.update().getMessage().getReplyToMessage().getMessageThreadId() != null) {
                            threadId = String.valueOf(ctx.update().getMessage().getReplyToMessage().getMessageThreadId());
                        } else if (ctx.update().getMessage().getMessageThreadId() != null &&
                                ctx.update().getMessage().getReplyToMessage() == null) {
                            threadId = String.valueOf(ctx.update().getMessage().getMessageThreadId());
                        }

                        String result = groupManagementService.removeGroupFromResponse(groupId, threadId);
                        SendMessage message = SendMessage
                                .builder()
                                .chatId(groupId)
                                .text(result)
                                .build();
                        if(threadId != null) {
                            message.setMessageThreadId(Integer.parseInt(threadId));
                        }
                        this.replyMessage(message);
                        
                        LoggingUtils.logOperation("STOP_RESPONSE_COMMAND", 
                            String.valueOf(ctx.user().getId()),
                            String.format("管理员禁用群聊回复功能 - GroupId: %s, ThreadId: %s", groupId, threadId));
                            
                    } catch (Exception e) {
                        LoggingUtils.logError("STOP_RESPONSE_ERROR", "禁用群聊回复功能失败", e);
                        silent.send("禁用群聊回复功能失败", ctx.chatId());
                    }
                })
                .build();
    }
    
    /**
     * 查看当前群聊的回复状态
     */
    public Ability checkResponseStatus() {
        return Ability
                .builder()
                .name("checkres")
                .info("查看当前群聊的回复状态")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        String groupId = String.valueOf(ctx.chatId());
                        String threadId = null;
                        String charName = ctx.update().getMessage().getChat().getTitle();

                        // 获取thread_id（如果有的话）
                        if (ctx.update().getMessage().getReplyToMessage() != null &&
                            ctx.update().getMessage().getReplyToMessage().getMessageThreadId() != null) {
                            threadId = String.valueOf(ctx.update().getMessage().getReplyToMessage().getMessageThreadId());
                        } else if (ctx.update().getMessage().getMessageThreadId() != null &&
                                   ctx.update().getMessage().getReplyToMessage() == null) {
                            threadId = String.valueOf(ctx.update().getMessage().getMessageThreadId());
                        }

                        String result = groupManagementService.getGroupResponseStatus(charName,groupId, threadId);
                        SendMessage message = SendMessage
                                .builder()
                                .chatId(groupId)
                                .text(result)
                                .build();
                        if(threadId != null) {
                            message.setMessageThreadId(Integer.parseInt(threadId));
                        }
                        this.replyMessage(message);
                        
                    } catch (Exception e) {
                        LoggingUtils.logError("CHECK_RESPONSE_STATUS_ERROR", "查看群聊回复状态失败", e);
                        silent.send("查看群聊回复状态失败", ctx.chatId());
                    }
                })
                .build();
    }

    /**
     * 查看当前的违禁词列表
     */
    public Ability forbidList() {
        return Ability
                .builder()
                .name("forbidlist")
                .info("查看当前的违禁词列表")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        String filePath = botConfig.getForbidUrl();
                        if (filePath == null || filePath.trim().isEmpty()) {
                            silent.send("未配置违禁词文件路径", ctx.chatId());
                            return;
                        }
                        Path path = Paths.get(filePath);
                        List<String> lines = Files.exists(path) ? Files.readAllLines(path, StandardCharsets.UTF_8) : new ArrayList<>();
                        List<String> words = new ArrayList<>();
                        for (String l : lines) {
                            if (l != null && !l.trim().isEmpty()) {
                                words.add(l.trim());
                            }
                        }
                        StringBuilder sb = new StringBuilder();
                        if (words.isEmpty()) {
                            sb.append("当前违禁词列表为空");
                        } else {
                            sb.append("当前违禁词（").append(words.size()).append(")：\n");
                            for (int i = 0; i < words.size(); i++) {
                                sb.append(i + 1).append(". ").append(words.get(i)).append("\n");
                            }
                        }
                        SendMessage message = SendMessage.builder()
                                .chatId(String.valueOf(ctx.chatId()))
                                .text(sb.toString())
                                .build();
                        this.replyMessage(message);
                        LoggingUtils.logOperation("FORBID_LIST", String.valueOf(ctx.chatId()), "查看违禁词列表");
                    } catch (Exception e) {
                        LoggingUtils.logError("FORBID_LIST_ERROR", "查看违禁词列表失败", e);
                        silent.send("查看违禁词列表失败", ctx.chatId());
                    }
                })
                .build();
    }

    /**
     * 删除单项违禁词
     */
    public Ability delForbid() {
        return Ability
                .builder()
                .name("delforbid")
                .info("删除单项违禁词：/delforbid 行号（从1开始）")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        String[] args = ctx.arguments();
                        if (args.length < 1) {
                            silent.send("用法：/delforbid 行号（从1开始）", ctx.chatId());
                            return;
                        }
                        int idx;
                        try {
                            idx = Integer.parseInt(args[0]);
                        } catch (NumberFormatException nfe) {
                            silent.send("行号必须是数字（从1开始）", ctx.chatId());
                            return;
                        }
                        if (idx < 1) {
                            silent.send("行号必须≥1", ctx.chatId());
                            return;
                        }

                        String filePath = botConfig.getForbidUrl();
                        if (filePath == null || filePath.trim().isEmpty()) {
                            silent.send("未配置违禁词文件路径", ctx.chatId());
                            return;
                        }
                        Path path = Paths.get(filePath);
                        List<String> lines = Files.exists(path) ? Files.readAllLines(path, StandardCharsets.UTF_8) : new ArrayList<>();
                        if (lines.isEmpty()) {
                            silent.send("当前违禁词列表为空", ctx.chatId());
                            return;
                        }
                        if (idx > lines.size()) {
                            silent.send("行号超出范围（当前共有 " + lines.size() + " 行）", ctx.chatId());
                            return;
                        }
                        String removedLine = lines.remove(idx - 1);
                        Files.write(path, lines, StandardCharsets.UTF_8);
                        SendMessage message = SendMessage.builder()
                                .chatId(String.valueOf(ctx.chatId()))
                                .text("已删除第" + idx + "行：" + removedLine)
                                .build();
                        this.replyMessage(message);
                        LoggingUtils.logOperation("FORBID_DELETE", String.valueOf(ctx.chatId()), "删除违禁词第" + idx + "行");
                    } catch (Exception e) {
                        LoggingUtils.logError("FORBID_DELETE_ERROR", "删除违禁词失败", e);
                        silent.send("删除违禁词失败", ctx.chatId());
                    }
                })
                .build();
    }

    /**
     * 添加一个违禁词
     */
    public Ability addForbid() {
        return Ability
                .builder()
                .name("addforbid")
                .info("添加一个违禁词：/addforbid 词")
                .locality(ALL)
                .privacy(Privacy.ADMIN)
                .action((ctx) -> {
                    try {
                        String[] args = ctx.arguments();
                        if (args.length < 1) {
                            silent.send("用法：/addforbid 违禁词", ctx.chatId());
                            return;
                        }
                        String input = String.join(" ", args).trim();
                        String target = cleanForbiddenWord(input);
                        if (target.isEmpty()) {
                            silent.send("无效的违禁词", ctx.chatId());
                            return;
                        }

                        String filePath = botConfig.getForbidUrl();
                        if (filePath == null || filePath.trim().isEmpty()) {
                            silent.send("未配置违禁词文件路径", ctx.chatId());
                            return;
                        }
                        Path path = Paths.get(filePath);
                        List<String> lines = Files.exists(path) ? Files.readAllLines(path, StandardCharsets.UTF_8) : new ArrayList<>();

                        // 重复性校验（按清洗逻辑对齐SqlService）
                        boolean exists = false;
                        for (String l : lines) {
                            if (cleanForbiddenWord(l).equals(target)) {
                                exists = true;
                                break;
                            }
                        }
                        if (exists) {
                            silent.send("该违禁词已存在", ctx.chatId());
                            return;
                        }

                        lines.add(input);
                        Files.write(path, lines, StandardCharsets.UTF_8);
                        SendMessage message = SendMessage.builder()
                                .chatId(String.valueOf(ctx.chatId()))
                                .text("已添加违禁词：" + input)
                                .build();
                        this.replyMessage(message);
                        LoggingUtils.logOperation("FORBID_ADD", String.valueOf(ctx.chatId()), "添加违禁词：" + input);
                    } catch (Exception e) {
                        LoggingUtils.logError("FORBID_ADD_ERROR", "添加违禁词失败", e);
                        silent.send("添加违禁词失败", ctx.chatId());
                    }
                })
                .build();
    }

    private String cleanForbiddenWord(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s,。;、]", "").toLowerCase();
    }

    /**
     * 处理积分排名分页回调
     */
    private void handlePointListCallback(Update update) {
        try {
            String callbackData = update.getCallbackQuery().getData();
            String[] parts = callbackData.split("_");
            
            if (parts.length >= 3) {
                int eventId = Integer.parseInt(parts[1]);
                int page = Integer.parseInt(parts[2]);
                
                String chatId = String.valueOf(update.getCallbackQuery().getMessage().getChatId());
                Map<String, Object> result = scoreService.getEventPointsRankingMessage(eventId, page, chatId);
                
                String text = (String) result.get("text");
                Object keyboard = result.get("keyboard");
                
                // 编辑原消息
                editMessageWithKeyboard(update.getCallbackQuery().getMessage().getMessageId(), chatId, text, keyboard);
            }
        } catch (Exception e) {
            LoggingUtils.logError("HANDLE_POINT_LIST_CALLBACK_ERROR", "处理积分排名回调失败", e);
        }
    }

    /**
     * 发送带内联键盘的消息
     */
    private void sendMessageWithKeyboard(String chatId, String text, Object keyboard) {
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build();
            
            if (keyboard != null) {
                message.getClass().getMethod("setReplyMarkup", 
                    Class.forName("org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard"))
                    .invoke(message, keyboard);
            }
            
            telegramClient.execute(message);
        } catch (Exception e) {
            LoggingUtils.logError("SEND_MESSAGE_WITH_KEYBOARD_ERROR", "发送带键盘消息失败", e);
        }
    }

    /**
     * 编辑消息和键盘
     */
    private void editMessageWithKeyboard(Integer messageId, String chatId, String text, Object keyboard) {
        try {
            Class<?> editMessageTextClass = Class.forName("org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText");
            Object editMessage = editMessageTextClass.getDeclaredConstructor().newInstance();
            
            editMessageTextClass.getMethod("setChatId", String.class).invoke(editMessage, chatId);
            editMessageTextClass.getMethod("setMessageId", Integer.class).invoke(editMessage, messageId);
            editMessageTextClass.getMethod("setText", String.class).invoke(editMessage, text);
            
            if (keyboard != null) {
                editMessageTextClass.getMethod("setReplyMarkup", 
                    Class.forName("org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup"))
                    .invoke(editMessage, keyboard);
            }
            
            // 使用反射调用execute方法
            telegramClient.getClass().getMethod("execute", 
                Class.forName("org.telegram.telegrambots.meta.api.methods.BotApiMethod"))
                .invoke(telegramClient, editMessage);
        } catch (Exception e) {
            LoggingUtils.logError("EDIT_MESSAGE_WITH_KEYBOARD_ERROR", "编辑消息键盘失败", e);
        }
    }
}