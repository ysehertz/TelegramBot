package com.bot.aabot.service;

import com.bot.aabot.entity.GPTAnswer;
import com.bot.aabot.entity.GuideMessage;
import com.bot.aabot.entity.TextChunk;
import com.bot.aabot.utils.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GPT服务，使用Spring AI统一接口实现模型切换、结构化输出和Chat Memory
 */
@Slf4j
@Service
public class GPTService {
    
    private final ChatModel chatModel;
    private final ChatClient simpleChatClient;      // 简单任务客户端
    private final ChatClient complexChatClient;     // 复杂任务客户端  
    private final ChatClient structuredChatClient;  // 结构化输出客户端
    private final ChatMemory chatMemory;
    private final QdrantClientService qdrantClientService;
    
    // 模型配置
    private static final String SIMPLE_MODEL = "gpt-4o-mini";
    private static final String COMPLEX_MODEL = "gpt-4o";
    
    @Value("${bot.ai.simple-temperature:0.1}")
    private double simpleTemperature;
    
    @Value("${bot.ai.complex-temperature:0.7}")
    private double complexTemperature;
    
    // 会话过期时间管理（30分钟）
    private static final long SESSION_TIMEOUT_MINUTES = 30;
    private final Map<String, LocalDateTime> sessionLastActivity = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    public GPTService(ChatModel chatModel, @Autowired(required = false) QdrantClientService qdrantClientService) {
        this.chatModel = chatModel;
        this.qdrantClientService = qdrantClientService;
        
        // 初始化Spring AI的Chat Memory（20条消息窗口）
        this.chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)  // 最多保持20条消息
            .build();
        
        // 创建不同用途的ChatClient
        this.simpleChatClient = ChatClient.builder(chatModel).build();
        
        // 复杂任务使用ChatMemory管理上下文
        this.complexChatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
            
        // 结构化输出任务也使用ChatMemory
        this.structuredChatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
        
        // 启动定时任务，每分钟清理过期会话
        scheduler.scheduleAtFixedRate(this::cleanExpiredSessions, 1, 1, TimeUnit.MINUTES);
        
        LoggingUtils.logSystemStatus("GPTService已初始化，使用Spring AI Chat Memory管理上下文");
    }

    /**
     * 判断文本是否为问题（使用GPT-4o-mini进行智能判断）
     * @param input 输入文本
     * @return 是否为问题
     */
    public boolean isQuestion(String input) {
        long startTime = System.currentTimeMillis();
        try {
            String response = simpleChatClient.prompt()
                .options(OpenAiChatOptions.builder()
                    .model(SIMPLE_MODEL)
                    .temperature(simpleTemperature)
                    .build())
                .user("你是一名专业知识丰富的社区管理人员，现在需要对下面的消息做出如下判断：**如何这条消息明显是一个专业性的提问，并且你在不了解历史聊天记录的情况下就可以对消息做出回答则返回`yes`，或者返回`no`**,仅返回 'yes' 或 'no'。消息内容：" + input)
                .call()
                .content();
            
            boolean result = response.trim().toLowerCase().contains("yes");
            LoggingUtils.logPerformance("isQuestion", startTime);
            return result;
        } catch (Exception e) {
            LoggingUtils.logError("IS_QUESTION_ERROR", "判断是否为问题失败", e);
            return false;
        }
    }
    /**
     * 判断文本是否为引用问题（使用GPT-4o-mini进行智能判断）
     * @param input 输入文本
     * @return 是否为问题
     */
    public boolean isQuoteQuestion(String input) {
        long startTime = System.currentTimeMillis();
        try {
            String response = simpleChatClient.prompt()
                    .options(OpenAiChatOptions.builder()
                            .model(SIMPLE_MODEL)
                            .temperature(simpleTemperature)
                            .build())
                    .user("你是一名专业知识丰富的社区管理人员，现在需要对下面的对话消息做出如下判断（这条互动消息是一定是一条机器人的专业知识讲解消息和一个用户的消息；消息格式为`机器人[<消息内容>],用户[<消息内容>]`）：**用户是否是对机器人的消息抛出了疑问，并且这个疑问适合你这个专业知识丰富的管理员回答，适合则返回`yes`，或者返回`no`**,仅允许返回 'yes' 或 'no'。消息内容：" + input)
                    .call()
                    .content();

            boolean result = response.trim().toLowerCase().contains("yes");
            LoggingUtils.logPerformance("isQuestion", startTime);
            return result;
        } catch (Exception e) {
            LoggingUtils.logError("IS_QUESTION_ERROR", "判断是否为问题失败", e);
            return false;
        }
    }

    /**
     * 检查指定session的消息队列中是否已经有对第一条消息的回复（使用GPT-4o-mini进行逻辑分析）
     * @param sessionId session标识
     * @return 是否已回复
     */
    public boolean isBeAnswered(String sessionId) {
        long startTime = System.currentTimeMillis();
        try {
            // 检查指定session队列是否为空或消息数量不足
            if (com.bot.aabot.context.MessageContext.getSessionQueueSize(sessionId) < 2) {
                LoggingUtils.logPerformance("isBeAnswered", startTime);
                return false;
            }
            
            // 获取指定session的消息进行分析
            List<com.bot.aabot.entity.TextMessageEntity> tempList = 
                com.bot.aabot.context.MessageContext.getSessionMessages(sessionId);
            
            if (tempList.isEmpty() || tempList.size() < 2) {
                LoggingUtils.logPerformance("isBeAnswered", startTime);
                return false;
            }
            
            // 获取第一条消息（最早的消息）
            com.bot.aabot.entity.TextMessageEntity firstMessage = tempList.get(0);
            
            // 构建用户消息，提供第一条消息和后续所有消息
            StringBuilder userMessage = new StringBuilder();
            userMessage.append("第一条消息:\n").append(firstMessage.getContent()).append("\n\n");
            userMessage.append("后续消息:\n");
            
            for (int i = 1; i < tempList.size(); i++) {
                com.bot.aabot.entity.TextMessageEntity message = tempList.get(i);
                userMessage.append("- ").append(message.getContent()).append("\n");
            }
            
            userMessage.append("\n请判断后续消息中是否有任何消息对一条消息进行了互动（可能是回答问题，也可能是对问题进行了延伸等等形式都属于互动）？请只回答'yes'或'no'。");
            
            // 获取AI分析结果
            String response = simpleChatClient.prompt()
                .options(OpenAiChatOptions.builder()
                    .model(SIMPLE_MODEL)
                    .temperature(simpleTemperature)
                    .build())
                .system("你是一个逻辑分析助手。你的任务是分析一组消息，判断其中是否存在对第一条消息的回答。" +
                        "请只返回'yes'或'no'，不要包含其他内容。")
                .user(userMessage.toString())
                .call()
                .content();
            
            boolean result = response.trim().toLowerCase().contains("yes");
            LoggingUtils.logPerformance("isBeAnswered", startTime);
            
            LoggingUtils.logBusinessOperation("MESSAGE_ANALYSIS", "SYSTEM", 
                String.format("分析Session[%s]消息队列回复状态，队列大小: %d，判断结果: %s", 
                    sessionId, tempList.size(), result ? "已回复" : "未回复"));
            
            return result;
            
        } catch (Exception e) {
            LoggingUtils.logError("IS_BE_ANSWERED_ERROR", 
                String.format("检查Session[%s]是否已回答失败", sessionId), e);
            return false;
        }
    }
    
    /**
     * 检查消息队列中是否已经有对第一条消息的回复（兼容旧版本，已废弃）
     * @deprecated 请使用 isBeAnswered(String sessionId) 方法
     */
    @Deprecated
    public boolean isBeAnswered() {
        // 由于架构变更，无法再获取全局队列，返回false
        LoggingUtils.logOperation("DEPRECATED_METHOD_CALL", "SYSTEM", 
            "调用了已废弃的isBeAnswered()方法，建议使用isBeAnswered(sessionId)");
        return false;
    }

    /**
     * 复杂任务：回答用户问题（使用gpt-4o获得更好效果，Spring AI Chat Memory管理上下文）
     */
    public GPTAnswer answerUserQuestion(String sessionId, String userQuestion) {
        long startTime = System.currentTimeMillis();
        try {
            // 更新会话活动时间
            updateSessionActivity(sessionId);
            
            // 获取相关文档
            List<TextChunk> relevantChunks = qdrantClientService != null ? 
                qdrantClientService.searchSimilarDocuments(userQuestion) : List.of();
            
            String context = relevantChunks.isEmpty() ? "" : 
                "参考信息：\\n" + relevantChunks.stream()
                    .map(TextChunk::getContent)
                    .reduce("", (a, b) -> a + "\\n" + b);
            
            String response = complexChatClient.prompt()
                .options(OpenAiChatOptions.builder()
                    .model(COMPLEX_MODEL)
                    .temperature(complexTemperature)
                    .build())
                .user(context + "\\n\\n问题：" + userQuestion)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

            LoggingUtils.logPerformance("answerUserQuestion", startTime);
            LoggingUtils.logBusinessOperation("CHAT_MEMORY", sessionId, 
                String.format("管理对话上下文，消息数量: %d", chatMemory.get(sessionId).size()));
            
            GPTAnswer answer = new GPTAnswer();
            answer.setSessionId(sessionId);
            answer.setAnswer(response);
            return answer;
        } catch (Exception e) {
            LoggingUtils.logError("ANSWER_USER_QUESTION_ERROR", "回答用户问题失败", e);
            
            GPTAnswer errorAnswer = new GPTAnswer();
            errorAnswer.setSessionId(sessionId);
            errorAnswer.setAnswer("抱歉，我无法回答您的问题。");
            return errorAnswer;
        }
    }
    
    /**
     * 结构化输出：回答用户问题并生成引导消息（直接返回GuideMessage对象，使用Chat Memory）
     */
    public GuideMessage answerUserQuestionWithAit(String sessionId, String userQuestion) {
        long startTime = System.currentTimeMillis();
        try {
            // 更新会话活动时间
            updateSessionActivity(sessionId);
            
            // 获取相关文档
            List<TextChunk> relevantChunks = qdrantClientService != null ? 
                qdrantClientService.searchSimilarDocuments(userQuestion) : List.of();
            
            String context = relevantChunks.isEmpty() ? "" : 
                "参考信息：\\n" + relevantChunks.stream()
                    .map(TextChunk::getContent)
                    .reduce("", (a, b) -> a + "\\n" + b);
            
            // 使用结构化输出转换器
            BeanOutputConverter<GuideMessage> outputConverter = new BeanOutputConverter<>(GuideMessage.class);
            
            String prompt = String.format(
                """
                %s
                
                问题：%s
                
                你必须使用与用户提问相同的语言进行回答。
                在你的回答中，如果遇到以下列表中的英文专业词汇，严禁将其翻译成任何其他语言，你必须以英文原文形式保留它们，以确保专业性和准确性：ama, apr, apy, base, bsc, btc, cake, cakepie, cefi, cex, dao, defi, dex, egp, eigenpie, ena, eqb, equilibria, eth, fomo, fud, gas, hpp, hyperpie, kol, lista, listapie, lp, ltp, magpie, mcake, mgp, mint, mpenlde, nft, pcs, pendle, penpie, pnp, rdnt, rdp, Rug, rush, sol, TVL
                请根据以上信息回答问题，并提供3个相关的引导问题建议，且每个建议的长度不超过60个字节。
                请按照以下JSON格式回答：
                %s
                """, 
                context, userQuestion, outputConverter.getFormat()
            );
            
            String response = structuredChatClient.prompt()
                .options(OpenAiChatOptions.builder()
                    .model(COMPLEX_MODEL)
                    .temperature(0.3) // 较低温度确保输出格式稳定
                    .build())
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
            
            // 直接转换为GuideMessage对象
            GuideMessage guideMessage = outputConverter.convert(response);
            
            LoggingUtils.logPerformance("answerUserQuestionWithAit", startTime);
            LoggingUtils.logBusinessOperation("STRUCTURED_OUTPUT_MEMORY", sessionId, 
                String.format("结构化输出对话，消息数量: %d", chatMemory.get(sessionId).size()));
            
            return guideMessage;
        } catch (Exception e) {
            LoggingUtils.logError("ANSWER_USER_QUESTION_WITH_AIT_ERROR", "回答用户问题（AIT）失败", e);
            
            // 创建默认的GuideMessage
            return GuideMessage.builder()
                .reply("Sorry, I cannot answer your question.")
                .guide1("Try rephrasing your question")
                .guide2("Provide more context")
                .guide3("Contact administrator")
                .build();
        }
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
            prompt = "你将收到一个 JSON 格式的数据，包含两部分：\n1. messages: 群聊消息数组，每条消息包含发言人姓名、发送时间和内容\n2. adminUsers: 管理员用户名列表\n\n请根据 messages 数组中的消息，帮助总结今天用户主要讨论了哪些话题。\n请遵守以下要求进行总结：\n1.  清晰列出今天群聊中主要的话题（不需要逐条复述消息）。\n2.  如果合适，请合并类似的对话内容，归为同一个话题。\n3.  总结应尽量简短、扼要，但要覆盖核心内容。\n4.  若谈话中出现了多个不同话题，请逐条列出，并使用项目符号或小标题标明。\n5.  不要添加虚构内容；只从提供的消息中提取信息。\n6.  总结要求使用英文。\n7.  请标出每个话题占当天讨论的比重\n\n响应示例：\n1. Magpie liquidity distribution on chains (approx. 30%)  \\n   - Users discussed whether Magpie's main liquidity lies on BSC or ARB chain. Current liquidity is said to be primarily on BSC, and BSC is noted to have slightly higher liquidity.\n2. Lending mechanism involving PENDLE and mPENDLE (approx. 20%)  \\n   - Users asked about where they can stake mPENDLE to borrow PENDLE, with Timeswap mentioned as a relevant platform.\n3. Timing of political bribery events (approx. 25%)  \\n   - Discussion on why bribery reports usually come out on Fridays and Saturdays, attributing it to weekend breaks and people's work rhythm starting from Tuesday.\n4. Reflections on Western work-life balance (approx. 15%)  \\n   - Comments on how 'foreigners live well' and how it feels like they 'only work three days a week.'\n5. Casual chat on borrowing risks and exit strategies (approx. 10%)  \\n   - Mentioned issues like 'borrowing PNP via VJPNP and not repaying,' indicating methods of fund exit.\n\n总体来看，讨论集中在加密货币链上活动与社区运作机制，但也包含部分轻松对话与社会文化感慨。 Overall, the conversations focused on crypto-related on-chain activities and community mechanisms, with a mix of casual and cultural observations.";
        } else {
            prompt = "你是一位群组活跃度分析助手，请根据以下 json 格式的数据，为管理员生成当天的互动表现分析与建议。\n\n输入数据包含两部分：\n1. messages: 群聊消息数组，每条消息包含发言人姓名、发送时间和内容\n2. adminUsers: 管理员用户名列表\n\n请你完成以下任务：\n1.  分析聊天内容，为今日群聊撰写一段简短的表现总结（从内容质量、活跃程度、气氛维持等进行评价）。\n2.  根据聊天内容，识别优秀用户若干名，并生成一个'优秀用户'列表。评选标准可以包括但不限于：积极参与讨论、有建设性的信息输出、帮助他人解答问题、引导话题深入、有助于建立友好氛围等。优秀用户不宜过多，一般控制在1～3人，若实在没有符合标准者可留空。**重要提醒：优秀用户不包括管理员用户。**\n3.  根据聊天内容，如有用户存在负面行为，请生成一个'表现恶劣用户'列表。判断标准可以包括但不限于：散播负面情绪、频繁打断或质疑他人、发布无关或低质量信息等。如果没有此类用户，请明确写'无'。如果有，请列出这些用户的名字并给出判定为恶劣用户的理由\n4.  请根据今日聊天内容给出1～2条'鼓励建议'，包括如何鼓励群成员更多交流、话题引导建议或互动形式优化等。\n5.  如有需要指出的改善建议（针对负面行为、内容质量或参与度等），请列出1～2条'惩罚建议'用于管理员参考，比如提醒用户注意发言质量、减少刷屏等。如无明显问题可写'无'。\n 6.使用英语进行总结\n请用以下格式输出结果：\n╭── 群聊日报 ──╮\n🏆 优秀用户：\n1.用户名A（简要说明理由）\n2.用户名B（如有）\n🚫 表现恶劣用户： （用户名 + 简要说明，或写'无'）\n\n响应示例：\n╭── Group Chat Daily Report ──╮\n🏆 Outstanding Users:\n\n- Lin Chester (Actively provided updates and followed up on task execution; helped clarify project developments and contributed to problem analysis such as potential allocation issues.)\n- xxx hahah (Frequently initiated relevant questions and discussions, helping drive group communication forward, especially around timing and bribery concerns.)\n\n🚫 Poorly Performing Users:\n\n- YieldGot (Displayed negative attitude by calling others \"菜鸡,\" which could harm group morale and derail constructive discussion.)\n\n💡 Encouragement Suggestions:\n\n1. Encourage users like Lin Chester and xxx hahah to continue engaging and lead more conversations — their involvement demonstrates initiative and value to the group.\n2. Consider organizing regular Q&A threads or voting update periods to help new or confused members feel more confident about asking questions without disrupting the flow.\n\n⚠️ Disciplinary Suggestions:\n\n1. Issue a reminder to all members to maintain respectful language and avoid derogatory comments like those from YieldGot.\n2. Encourage members to stay focused on constructive input and avoid speculative or unhelpful commentary that doesn't contribute to group goals.";
        }
        try {
            String response = simpleChatClient.prompt()
                    .system(prompt)
                    .user("输入数据：" + json)
                    .call()
                    .content();
            return response;
        } catch (Exception e) {
            return "AI总结失败：" + e.getMessage();
        }
    }

    /**
     * 更新会话活动时间
     */
    private void updateSessionActivity(String sessionId) {
        sessionLastActivity.put(sessionId, LocalDateTime.now());
    }

    /**
     * 清理过期的会话（30分钟未活动）
     */
    private void cleanExpiredSessions() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int removedCount = 0;
            
            for (var entry : sessionLastActivity.entrySet()) {
                String sessionId = entry.getKey();
                LocalDateTime lastActivity = entry.getValue();
                
                if (lastActivity.plusMinutes(SESSION_TIMEOUT_MINUTES).isBefore(now)) {
                    // 清理过期会话
                    chatMemory.clear(sessionId);
                    sessionLastActivity.remove(sessionId);
                    removedCount++;
                    
                    LoggingUtils.logBusinessOperation("SESSION_CLEANUP", sessionId, 
                        String.format("清理过期会话，最后活动时间: %s", lastActivity));
                }
            }
            
            if (removedCount > 0) {
                LoggingUtils.logSystemStatus(String.format("清理了%d个过期会话", removedCount));
            }
        } catch (Exception e) {
            LoggingUtils.logError("SESSION_CLEANUP_ERROR", "清理过期会话失败", e);
        }
    }

    /**
     * 获取用户会话历史
     */
    public List<org.springframework.ai.chat.messages.Message> getConversationHistory(String sessionId) {
        return chatMemory.get(sessionId);
    }

    /**
     * 清除用户会话历史
     */
    public void clearConversationHistory(String sessionId) {
        chatMemory.clear(sessionId);
        sessionLastActivity.remove(sessionId);
        LoggingUtils.logBusinessOperation("CLEAR_CONVERSATION", sessionId, "手动清除会话历史");
    }

    /**
     * 清除所有用户会话
     * @return 清除的会话数量
     */
    public int clearAllConversations() {
        try {
            int count = sessionLastActivity.size();
            sessionLastActivity.clear();
            // 注意：InMemoryChatMemory没有直接的clearAll方法，需要逐个清理
            LoggingUtils.logSystemStatus("清除了所有用户会话，共" + count + "个");
            return count;
        } catch (Exception e) {
            LoggingUtils.logError("SESSION_CLEAR_ALL_ERROR", "清除所有会话失败", e);
            return 0;
        }
    }
}