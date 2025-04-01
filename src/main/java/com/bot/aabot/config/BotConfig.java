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