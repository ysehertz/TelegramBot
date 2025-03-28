package com.bot.aabot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 知识库配置类
 */
@Data
@Configuration
public class KnowledgeBaseConfig {
    
    /**
     * 知识库文件夹路径
     */
    @Value("${bot.knowledge.directory-path}")
    private String knowledgeDirectoryPath;
    
    /**
     * 支持的文件类型（逗号分隔）
     */
    @Value("${bot.knowledge.file-types}")
    private String fileTypesString;
    
    /**
     * 是否递归搜索子文件夹
     */
    @Value("${bot.knowledge.recursive}")
    private boolean recursive;
    
    /**
     * 知识库更新频率（分钟）
     */
    @Value("${bot.knowledge.update-interval}")
    private int updateInterval;
    
    /**
     * 文本块大小（字符数）
     */
    @Value("${bot.knowledge.chunk-size}")
    private int chunkSize;
    
    /**
     * 文本块重叠大小（字符数）
     */
    @Value("${bot.knowledge.chunk-overlap}")
    private int chunkOverlap;
    
    /**
     * 相似度检索结果数量
     */
    @Value("${bot.knowledge.top-k}")
    private int topK;
    
    /**
     * 相似度阈值
     */
    @Value("${bot.knowledge.similarity-threshold}")
    private double similarityThreshold;
    
    /**
     * OpenAI嵌入模型名称
     */
    @Value("${bot.knowledge.embedding-model}")
    private String embeddingModel;
    
    /**
     * 获取支持的文件类型列表
     */
    public List<String> getSupportedFileTypes() {
        return Arrays.asList(fileTypesString.split(","));
    }
} 