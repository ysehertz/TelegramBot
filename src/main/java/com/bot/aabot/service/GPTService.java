package com.bot.aabot.service;

import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.TextChunk;
import com.bot.aabot.initializer.BotContext;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import lombok.extern.slf4j.Slf4j;
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
    
    private final VectorStoreService vectorStoreService;
    
    // 存储用户会话的Map，键为用户ID，值为用户会话对象
    private final Map<String, UserConversation> userConversations = new ConcurrentHashMap<>();
    
    // 定时清理过期会话的调度器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    public GPTService(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
        // 启动定时任务，每分钟检查一次过期会话
        scheduler.scheduleAtFixedRate(this::cleanExpiredConversations, 1, 1, TimeUnit.MINUTES);
    }
    
    /**
     * 判断输入字符串是否为问题
     */
    public boolean isQuestion(String input) {
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

        return "yes".equals(response);
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
        
        // 从知识库检索相关内容
        List<TextChunk> relevantChunks = vectorStoreService.isEmpty() 
                ? List.of() 
                : vectorStoreService.similaritySearch(question);
        
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
            
            prompt.append("请基于以上知识库内容回答用户问题。如果知识库中没有相关信息，请使用你自己的知识提供回答，但请明确指出这部分不是来自知识库。");
        }
        
        return prompt.toString();
    }
    
    /**
     * 清理过期的会话
     */
    private void cleanExpiredConversations() {
        LocalDateTime now = LocalDateTime.now();
        userConversations.entrySet().removeIf(entry -> {
            LocalDateTime lastActiveTime = entry.getValue().getLastActiveTime();
            return lastActiveTime.plusMinutes(BotContext.ConversationTimeout).isBefore(now);
        });
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
