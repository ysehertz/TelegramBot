package com.bot.aabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "bot")
public class BotConfig {
    private String oneOrEveryday;
    private Long createId;
    private String forbidUrl; // 添加禁止词文件路径
    private boolean aiInteraction = false; // 是否开启AI互动功能，默认关闭
    private MessageConfig message;
    private KnowledgeConfig knowledge;

    @Data
    public static class MessageConfig {
        private int conversation_timeout;
        private int max_context;
    }

    @Data
    public static class KnowledgeConfig {
        private String directoryPath;
        private String fileTypes;
        private boolean recursive;
        private int updateInterval;
        private int chunkSize;
        private int chunkOverlap;
        private int topK;
        private double similarityThreshold;
        private String embeddingModel;
    }
} 