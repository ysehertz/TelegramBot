package com.bot.aabot.config;

import lombok.Data;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 知识库配置类
 */
@Data
@Configuration
public class KnowledgeBaseConfig {
    
    private final BotConfig botConfig;
    
    public KnowledgeBaseConfig(BotConfig botConfig) {
        this.botConfig = botConfig;
    }
    
    public String getKnowledgeDirectoryPath() {
        return botConfig.getKnowledge().getDirectoryPath();
    }
    
    public void setKnowledgeDirectoryPath(String path) {
        botConfig.getKnowledge().setDirectoryPath(path);
    }
    
    public String getFileTypesString() {
        return botConfig.getKnowledge().getFileTypes();
    }
    
    public void setFileTypesString(String fileTypes) {
        botConfig.getKnowledge().setFileTypes(fileTypes);
    }
    
    public boolean isRecursive() {
        return botConfig.getKnowledge().isRecursive();
    }
    
    public void setRecursive(boolean recursive) {
        botConfig.getKnowledge().setRecursive(recursive);
    }
    
    public int getUpdateInterval() {
        return botConfig.getKnowledge().getUpdateInterval();
    }
    
    public int getChunkSize() {
        return botConfig.getKnowledge().getChunkSize();
    }
    
    public int getChunkOverlap() {
        return botConfig.getKnowledge().getChunkOverlap();
    }
    
    public int getTopK() {
        return botConfig.getKnowledge().getTopK();
    }
    
    public double getSimilarityThreshold() {
        return botConfig.getKnowledge().getSimilarityThreshold();
    }
    
    public String getEmbeddingModel() {
        return botConfig.getKnowledge().getEmbeddingModel();
    }
    
    public List<String> getSupportedFileTypes() {
        return Arrays.asList(getFileTypesString().split(","));
    }
} 