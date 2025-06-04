package com.bot.aabot.service;

import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.TextChunk;
import com.bot.aabot.entity.TextMessageEntity;
import com.bot.aabot.context.MessageContext;
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
        prompt.append("你是一个知识渊博且友好的助理，请根据用户的问题，用英语提供一个简略的回答，并给出三个提示短语，你只能用英语进行回答。回答格式为：`{\"reply\":\"简略回答\",\"guide1\":\"提示短语1\",\"guide2\":\"提示短语2\",\"guide3\":\"提示短语3\"}`示例：用户:hello,回答:`{\"reply\":\"Hello there! How can I assist you today?\",\"guide1\":\"Ask about Eigenlayer website\",\"guide2\":\"Inquire about Magpie event\",\"guide3\":\"Learn about Discord community\"}`");

        if (!relevantChunks.isEmpty()) {
            prompt.append("\n\n以下是与用户问题相关的知识库内容，请在回答时优先使用这些信息：\n\n");

            for (int i = 0; i < relevantChunks.size(); i++) {
                prompt.append("--- 相关信息 ").append(i + 1).append(" ---\n");
                prompt.append(relevantChunks.get(i).getContent()).append("\n\n");
            }

            prompt.append("请基于以上知识库内容回答用户问题。如果知识库中没有相关信息，请使用你自己的知识提供回答，但请在JSON格式的reply字段中指出你的回答是否参考了知识库还是只有你自己的知识。所有回答必须严格按照指定的以`{`开始`}`结尾的JSON格式。");
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
        // 检查消息队列是否为空或消息数量不足
        if (MessageContext.getQueueSize() == 0 || MessageContext.getQueueSize() < 2) {
            return false;
        }
        
        // 由于ConcurrentLinkedDeque不支持索引访问，我们需要创建临时列表来检查消息
        // 注意：这个操作在高并发下可能不完全准确，但对于判断是否有回复来说是可接受的
        List<TextMessageEntity> tempList = new ArrayList<>(MessageContext.messageContextQueue);
        
        if (tempList.isEmpty() || tempList.size() < 2) {
            return false;
        }
        
        // 获取第一条消息（最早的消息）
        TextMessageEntity firstMessage = tempList.get(0);
        
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
        
        for (int i = 1; i < tempList.size(); i++) {
            TextMessageEntity message = tempList.get(i);
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
     * 群聊消息总结/用户分析（不查知识库，含响应示例）
     * @param json 消息json
     * @param type "summary"或"user"
     * @return AI回复内容
     */
    public String summarizeGroupMessages(String json, String type) {
        String prompt;
        if ("summary".equals(type)) {
            prompt = "你将收到一个 JSON 格式的数组，其中每一条是今天群聊中的一段消息，包括发言人姓名、发送时间和内容。请根据所有消息，帮助总结今天用户主要讨论了哪些话题。\n请遵守以下要求进行总结：\n1.  清晰列出今天群聊中主要的话题（不需要逐条复述消息）。\n2.  如果合适，请合并类似的对话内容，归为同一个话题。\n3.  总结应尽量简短、扼要，但要覆盖核心内容。\n4.  若谈话中出现了多个不同话题，请逐条列出，并使用项目符号或小标题标明。\n5.  不要添加虚构内容；只从提供的消息中提取信息。\n6.  总结要求使用英文。\n7.  请标出每个话题占当天讨论的比重\n\n响应示例：\n1. Magpie liquidity distribution on chains (approx. 30%)  \\n   - Users discussed whether Magpie's main liquidity lies on BSC or ARB chain. Current liquidity is said to be primarily on BSC, and BSC is noted to have slightly higher liquidity.\n2. Lending mechanism involving PENDLE and mPENDLE (approx. 20%)  \\n   - Users asked about where they can stake mPENDLE to borrow PENDLE, with Timeswap mentioned as a relevant platform.\n3. Timing of political bribery events (approx. 25%)  \\n   - Discussion on why bribery reports usually come out on Fridays and Saturdays, attributing it to weekend breaks and people's work rhythm starting from Tuesday.\n4. Reflections on Western work-life balance (approx. 15%)  \\n   - Comments on how 'foreigners live well' and how it feels like they 'only work three days a week.'\n5. Casual chat on borrowing risks and exit strategies (approx. 10%)  \\n   - Mentioned issues like 'borrowing PNP via VJPNP and not repaying,' indicating methods of fund exit.\n\n总体来看，讨论集中在加密货币链上活动与社区运作机制，但也包含部分轻松对话与社会文化感慨。 Overall, the conversations focused on crypto-related on-chain activities and community mechanisms, with a mix of casual and cultural observations.";
        } else {
            prompt = "你是一位群组活跃度分析助手，请根据以下 json 格式的群聊消息，为管理员生成当天的互动表现分析与建议。\n请你完成以下任务：\n1.  分析聊天内容，为今日群聊撰写一段简短的表现总结（从内容质量、活跃程度、气氛维持等进行评价）。\n2.  根据聊天内容，识别优秀用户若干名，并生成一个'优秀用户'列表。评选标准可以包括但不限于：积极参与讨论、有建设性的信息输出、帮助他人解答问题、引导话题深入、有助于建立友好氛围等。优秀用户不宜过多，一般控制在1～3人，若实在没有符合标准者可留空。\n3.  根据聊天内容，如有用户存在负面行为，请生成一个'表现恶劣用户'列表。判断标准可以包括但不限于：散播负面情绪、频繁打断或质疑他人、发布无关或低质量信息等。如果没有此类用户，请明确写'无'。\n4.  请根据今日聊天内容给出1～2条'鼓励建议'，包括如何鼓励群成员更多交流、话题引导建议或互动形式优化等。\n5.  如有需要指出的改善建议（针对负面行为、内容质量或参与度等），请列出1～2条'惩罚建议'用于管理员参考，比如提醒用户注意发言质量、减少刷屏等。如无明显问题可写'无'。\n请用以下格式输出结果：\n╭── 群聊日报 ──╮\n🏆 优秀用户：\n1.用户名A（简要说明理由）\n2.用户名B（如有）\n🚫 表现恶劣用户： （用户名 + 简要说明，或写'无'）\n\n响应示例：\n╭── 群聊日报 ──╮  \\n🏆 优秀用户：  \\n*   Lin Chester（持续为群成员解答关于 BSC 与 ARB 链流动性、排放异常、平台使用等技术性问题，输出有深度并保持耐心，体现了专业性与帮助意识）  \\n*   xxx hahah（积极发起疑问并引导多个关键话题，如 Magpie 主流动性走向、贿赂制度现况，调动了群内对项目的讨论热度）\n🚫 表现恶劣用户：  \\n无  \\n以下是今日群聊的消息记录总结：\n1. 聊天内容涵盖区块链流动性分析、平台排放机制、贿赂分配与抵押使用场景等多个项目关键话题，整体内容质量较高；\n2. 群内在不同时间段保持连续互动，部分成员积极询问并跟进项目发展，说明群整体活跃度良好；\n3. 氛围方面虽有表达不满如'真的菜鸡'等情绪性言论，但未出现人身攻击或恶意刷屏，属于可接受范围，对整体气氛影响不大。\n📈 鼓励建议：  \\n1. 管理员可定期发起问答或 AMA（Ask Me Anything）活动，鼓励像 Lin Chester 等技术型用户输出知识内容，提高知识共享度。  \\n2. 对于项目动态，建议每周固定时间整理一次简报（如'本周流动性观察'、'贿赂变化趋势'），提升群聊的内容参考价值。\n⚠️ 惩罚建议：  \\n无";
        }
        try {
            List<com.theokanning.openai.completion.chat.ChatMessage> messages = new ArrayList<>();
            messages.add(new com.theokanning.openai.completion.chat.ChatMessage("system", prompt));
            messages.add(new com.theokanning.openai.completion.chat.ChatMessage("user", "消息json：" + json));
            com.theokanning.openai.completion.chat.ChatCompletionRequest chatCompletionRequest = com.theokanning.openai.completion.chat.ChatCompletionRequest
                    .builder()
                    .model("gpt-4o")
                    .messages(messages)
                    .build();
            com.theokanning.openai.service.OpenAiService service = new com.theokanning.openai.service.OpenAiService(openaiApiKey);
            String response = service.createChatCompletion(chatCompletionRequest)
                    .getChoices().get(0).getMessage().getContent();
            return response;
        } catch (Exception e) {
            return "AI总结失败：" + e.getMessage();
        }
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
