package com.bot.aabot.service;

import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.TextChunk;
import com.bot.aabot.initializer.BotContext;
import com.bot.aabot.utils.LoggingUtils;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GPTService {
    
    @Value("${openai.api-key}")
    private String openaiApiKey;
    @Value("${bot.knowledge.use-qdrant}")
    private boolean useQdrant;

    @Autowired
    private QdrantClientService qdrantSearchService;
    
    // 存储用户会话的Map，键为用户ID，值为用户会话对象
    private final Map<String, UserConversation> userConversations = new ConcurrentHashMap<>();
    
    // 定时清理过期会话的调度器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    public GPTService() {
        // 启动定时任务，每分钟检查一次过期会话
        scheduler.scheduleAtFixedRate(this::cleanExpiredConversations, 1, 1, TimeUnit.MINUTES);
    }
    
    /**
     * 判断输入字符串是否为问题
     */
    public boolean isQuestion(String input) {
        long startTime = System.currentTimeMillis();
        try {
            OpenAiService service = new OpenAiService(openaiApiKey);
            
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage("system", "你是一个消息分类助手。请判断下面的消息是否是一个问题或者提问或者请求。仅返回 'yes' 或 'no'。"));
            messages.add(new ChatMessage("user", input));

            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                    .builder()
                    .model("gpt-4o-mini")
                    .messages(messages)
                    .build();

            String response = service.createChatCompletion(chatCompletionRequest)
                    .getChoices().get(0).getMessage().getContent().trim().toLowerCase();

            LoggingUtils.logPerformance("isQuestion", startTime);
            return "yes".equals(response);
        } catch (Exception e) {
            LoggingUtils.logError("GPT_API_ERROR", "判断问题类型失败", e);
            return false;
        }
    }

    /**
     * 当用户@机器人时回答用户问题，考虑对话上下文，并结合知识库提供更准确的回答
     * @param userId 用户ID
     * @param question 用户问题
     * @return GPTAnswer对象，包含AI回答和会话ID
     */
    public GPTAnswer answerUserQuestionWithAit(String userId, String question) {
        long startTime = System.currentTimeMillis();
        try {
            // 获取或创建用户会话
            UserConversation conversation = userConversations.computeIfAbsent(
                    userId, id -> new UserConversation(userId)
            );

            // 更新最后活动时间
            conversation.updateLastActiveTime();

            // 将用户问题添加到会话
            conversation.addMessage(new ChatMessage("user", question));

            // 准备完整的对话历史
            List<ChatMessage> conversationHistory = new ArrayList<>(conversation.getMessages());

            // 从知识库检索相关内容（如果启用了Qdrant）
            List<TextChunk> relevantChunks = new ArrayList<>();
            if (useQdrant) {
                LoggingUtils.logOperation("KNOWLEDGE_SEARCH", userId, "开始检索知识库");
                relevantChunks = qdrantSearchService.searchSimilarDocuments(question);

                if (!relevantChunks.isEmpty()) {
                    LoggingUtils.logOperation("KNOWLEDGE_SEARCH", userId, "检索到" + relevantChunks.size() + "个相关文档");
                } else {
                    LoggingUtils.logOperation("KNOWLEDGE_SEARCH", userId, "未找到相关内容");
                }
            }

            // 构建系统提示，包含知识库相关内容
            String systemPrompt = buildSystemPromptWithAit(relevantChunks);

            // 在对话历史开头添加系统消息
            conversationHistory.add(0, new ChatMessage("system", systemPrompt));

            // 调用OpenAI API获取回答
            OpenAiService service = new OpenAiService(openaiApiKey);

            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                    .builder()
                    .model("gpt-4o")
                    .messages(conversationHistory)
                    .build();

            // 获取AI响应
            String response = service.createChatCompletion(chatCompletionRequest)
                    .getChoices().get(0).getMessage().getContent();

            // 将AI回答添加到会话
            conversation.addMessage(new ChatMessage("assistant", response));

            // 创建并返回GPTAnswer对象
            GPTAnswer gptAnswer = new GPTAnswer();
            gptAnswer.setAnswer(response);
            gptAnswer.setSessionId(userId + "_" + conversation.getCreationTimestamp());

            LoggingUtils.logPerformance("answerUserQuestionWithAit", startTime);
            return gptAnswer;
        } catch (Exception e) {
            LoggingUtils.logError("GPT_API_ERROR", "回答用户问题失败", e);
            GPTAnswer errorAnswer = new GPTAnswer();
            errorAnswer.setAnswer("抱歉，处理您的请求时出现错误，请稍后再试。");
            errorAnswer.setSessionId(userId + "_error");
            return errorAnswer;
        }
    }

    /**
     * 回答用户问题，考虑对话上下文，并结合知识库提供更准确的回答
     * @param userId 用户ID
     * @param question 用户问题
     * @return GPTAnswer对象，包含AI回答和会话ID
     */
    public GPTAnswer answerUserQuestion(String userId, String question) {
        // 获取或创建用户会话
        UserConversation conversation = userConversations.computeIfAbsent(
            userId, id -> new UserConversation(userId)
        );
        
        // 更新最后活动时间
        conversation.updateLastActiveTime();
        
        // 将用户问题添加到会话
        conversation.addMessage(new ChatMessage("user", question));
        
        // 准备完整的对话历史
        List<ChatMessage> conversationHistory = new ArrayList<>(conversation.getMessages());

        // 从知识库检索相关内容（如果启用了Qdrant）
        List<TextChunk> relevantChunks = new ArrayList<>();
        if (useQdrant) {
            log.info("正在从Qdrant知识库中检索与问题相关的内容: {}", question);
            relevantChunks = qdrantSearchService.searchSimilarDocuments(question);

            if (!relevantChunks.isEmpty()) {
                log.info("从知识库检索到{}个相关文档", relevantChunks.size());
            } else {
                log.info("知识库中未找到相关内容");
            }
        }

        // 构建系统提示，包含知识库相关内容
        String systemPrompt = buildSystemPrompt(relevantChunks);

        // 在对话历史开头添加系统消息
        conversationHistory.add(0, new ChatMessage("system", systemPrompt));
        
        // 调用OpenAI API获取回答
        OpenAiService service = new OpenAiService(openaiApiKey);
        
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                .builder()
                .model("gpt-4o")
                .messages(conversationHistory)
                .build();
        
        // 获取AI响应
        String response = service.createChatCompletion(chatCompletionRequest)
                .getChoices().get(0).getMessage().getContent();
        
        // 将AI回答添加到会话
        conversation.addMessage(new ChatMessage("assistant", response));
        
        // 创建并返回GPTAnswer对象
        GPTAnswer gptAnswer = new GPTAnswer();
        gptAnswer.setAnswer(response);
        gptAnswer.setSessionId(userId + "_" + conversation.getCreationTimestamp());
        
        return gptAnswer;
    }

    private String buildSystemPromptWithAit(List<TextChunk> relevantChunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个知识渊博且友好的助理，请根据用户的问题，用英语提供一个简略的回答，并给出三个提示短语，你只能用英语进行回答。回答格式为：{\"reply\":\"简略回答\",\"guide1\":\"提示短语1\",\"guide2\":\"提示短语2\",\"guide3\":\"提示短语3\"}");

        if (!relevantChunks.isEmpty()) {
            prompt.append("\n\n以下是与用户问题相关的知识库内容，请在回答时优先使用这些信息：\n\n");

            for (int i = 0; i < relevantChunks.size(); i++) {
                prompt.append("--- 相关信息 ").append(i + 1).append(" ---\n");
                prompt.append(relevantChunks.get(i).getContent()).append("\n\n");
            }

            prompt.append("请基于以上知识库内容回答用户问题。如果知识库中没有相关信息，请使用你自己的知识提供回答，但请在JSON格式的reply字段中指出你的回答是否参考了知识库还是只有你自己的知识。所有回答必须严格按照指定的JSON格式。");
        }

        return prompt.toString();
    }

    /**
     * 构建系统提示，包含知识库相关内容
     */
    private String buildSystemPrompt(List<TextChunk> relevantChunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个知识渊博且友好的助理，请根据用户的问题，用英语提供详细且准确的回答，你只能用英语进行回答。");
        
        if (!relevantChunks.isEmpty()) {
            prompt.append("\n\n以下是与用户问题相关的知识库内容，请在回答时优先使用这些信息：\n\n");
            
            for (int i = 0; i < relevantChunks.size(); i++) {
                prompt.append("--- 相关信息 ").append(i + 1).append(" ---\n");
                prompt.append(relevantChunks.get(i).getContent()).append("\n\n");
            }
            
            prompt.append("请基于以上知识库内容回答用户问题。如果知识库中没有相关信息，请使用你自己的知识提供回答，但请一定在回答的第一句中指出你的回答是否参考了知识库还是只有你自己的知识。");
        }
        
        return prompt.toString();
    }
    
    /**
     * 清理过期的会话
     */
    private void cleanExpiredConversations() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int removedCount = 0;
            for (var entry : userConversations.entrySet()) {
                LocalDateTime lastActiveTime = entry.getValue().getLastActiveTime();
                if (lastActiveTime.plusMinutes(BotContext.ConversationTimeout).isBefore(now)) {
                    userConversations.remove(entry.getKey());
                    removedCount++;
                }
            }
            if (removedCount > 0) {
                LoggingUtils.logSystemStatus("清理了" + removedCount + "个过期会话");
            }
        } catch (Exception e) {
            LoggingUtils.logError("SESSION_CLEANUP", "清理过期会话失败", e);
        }
    }
    
    /**
     * 清除所有用户会话
     * @return 清除的会话数量
     */
    public int clearAllConversations() {
        try {
            int count = userConversations.size();
            userConversations.clear();
            LoggingUtils.logSystemStatus("清除了所有用户会话，共" + count + "个");
            return count;
        } catch (Exception e) {
            LoggingUtils.logError("SESSION_CLEAR", "清除所有会话失败", e);
            return 0;
        }
    }

    public boolean isBeAnswered() {
        // 获取消息上下文列表
        if (com.bot.aabot.context.MessageContext.messageContextList.isEmpty() || 
            com.bot.aabot.context.MessageContext.messageContextList.size() < 2) {
            return false;
        }
        
        // 获取第一条消息
        com.bot.aabot.entity.TextMessageEntity firstMessage = com.bot.aabot.context.MessageContext.messageContextList.get(0);
        
        OpenAiService service = new OpenAiService(openaiApiKey);
        
        // 构建消息列表，让ChatGPT判断
        List<ChatMessage> messages = new ArrayList<>();
        
        // 系统提示，告诉GPT它需要做什么
        messages.add(new ChatMessage("system", "你是一个逻辑分析助手。你的任务是分析一组消息，判断其中是否存在对第一条消息的逻辑回复。" +
                "请只返回'yes'或'no'，不要包含其他内容。"));
        
        // 用户消息，提供第一条消息和后续所有消息
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("第一条消息:\n").append(firstMessage.getContent()).append("\n\n");
        userMessage.append("后续消息:\n");
        
        for (int i = 1; i < com.bot.aabot.context.MessageContext.messageContextList.size(); i++) {
            com.bot.aabot.entity.TextMessageEntity message = com.bot.aabot.context.MessageContext.messageContextList.get(i);
            userMessage.append("- ").append(message.getContent()).append("\n");
        }
        
        userMessage.append("\n请判断后续消息中是否有对第一条消息的逻辑回复？请只回答'yes'或'no'。");
        
        messages.add(new ChatMessage("user", userMessage.toString()));
        
        // 创建请求
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                .builder()
                .model("gpt-4o-mini")
                .messages(messages)
                .build();
        
        // 获取响应
        String response = service.createChatCompletion(chatCompletionRequest)
                .getChoices().get(0).getMessage().getContent().trim().toLowerCase();
        
        log.info("GPT判断消息是否有回复的结果: {}", response);
        
        return "yes".equals(response);
    }

    /**
     * 用户会话类，存储用户的对话历史
     */
    private static class UserConversation {
        private final String userId;
        private final List<ChatMessage> messages = new ArrayList<>();
        private LocalDateTime lastActiveTime;
        private final long creationTimestamp;
        
        public UserConversation(String userId) {
            this.userId = userId;
            this.lastActiveTime = LocalDateTime.now();
            this.creationTimestamp = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        
        public List<ChatMessage> getMessages() {
            return messages;
        }
        
        public LocalDateTime getLastActiveTime() {
            return lastActiveTime;
        }
        
        public void updateLastActiveTime() {
            this.lastActiveTime = LocalDateTime.now();
        }
        
        public long getCreationTimestamp() {
            return creationTimestamp;
        }
        
        public void addMessage(ChatMessage message) {
            messages.add(message);
            // 如果消息数量超过最大限制，移除最早的消息
            while (messages.size() > BotContext.MaxContext) {
                messages.remove(0);
            }
        }
    }
}
