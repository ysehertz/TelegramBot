package com.bot.aabot.service;

import com.bot.aabot.config.BotConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigManagementService {
    
    private final BotConfig botConfig;
    private final ObjectMapper objectMapper;
    private final YAMLFactory yamlFactory;
    
    private static final String CONFIG_FILE_NAME = "bot-config.yml";
    
    /**
     * 获取配置文件的路径
     */
    private String getConfigFilePath() throws IOException {
        // 首先尝试从classpath获取文件
        ClassPathResource resource = new ClassPathResource(CONFIG_FILE_NAME);
        if (resource.exists()) {
            // 获取文件的实际路径
            File file = resource.getFile();
            return file.getAbsolutePath();
        }
        // 如果文件不存在，则创建在classpath目录下
        String classpathDir = resource.getClassLoader().getResource("").getPath();
        return Paths.get(classpathDir, CONFIG_FILE_NAME).toString();
    }
    
    /**
     * 更新配置并持久化到文件
     */
    public void updateConfig(Map<String, Object> updates) throws IOException {
        Map<String, Object> update = new HashMap<>();
        update.put("bot", updates);
        // 创建新的配置Map
        Map<String, Object> newConfig = new HashMap<>();
        
        // 复制当前配置
        newConfig.put("bot", createBotConfigMap());
        
        // 更新配置
        updateConfigMap(newConfig, update);
        
        // 将更新后的配置写回文件
        writeConfigToFile(newConfig);
        
        // 更新内存中的配置
        updateBotConfig(newConfig);
    }
    
    /**
     * 创建bot配置的Map
     */
    private Map<String, Object> createBotConfigMap() {
        Map<String, Object> botMap = new HashMap<>();
        botMap.put("oneOrEveryday", botConfig.getOneOrEveryday());
        botMap.put("createId", botConfig.getCreateId());
        
        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put("conversation_timeout", botConfig.getMessage().getConversation_timeout());
        messageMap.put("max_context", botConfig.getMessage().getMax_context());
        botMap.put("message", messageMap);
        
        Map<String, Object> knowledgeMap = new HashMap<>();
        knowledgeMap.put("directoryPath", botConfig.getKnowledge().getDirectoryPath());
        knowledgeMap.put("fileTypes", botConfig.getKnowledge().getFileTypes());
        knowledgeMap.put("recursive", botConfig.getKnowledge().isRecursive());
        knowledgeMap.put("updateInterval", botConfig.getKnowledge().getUpdateInterval());
        knowledgeMap.put("chunkSize", botConfig.getKnowledge().getChunkSize());
        knowledgeMap.put("chunkOverlap", botConfig.getKnowledge().getChunkOverlap());
        knowledgeMap.put("topK", botConfig.getKnowledge().getTopK());
        knowledgeMap.put("similarityThreshold", botConfig.getKnowledge().getSimilarityThreshold());
        knowledgeMap.put("embeddingModel", botConfig.getKnowledge().getEmbeddingModel());
        botMap.put("knowledge", knowledgeMap);
        
        return botMap;
    }
    
    /**
     * 递归更新配置Map
     */
    private void updateConfigMap(Map<String, Object> currentConfig, Map<String, Object> updates) {
        updates.forEach((key, value) -> {
            if (value instanceof Map) {
                Map<String, Object> currentValue = (Map<String, Object>) currentConfig.get(key);
                if (currentValue == null) {
                    currentValue = new HashMap<>();
                }
                updateConfigMap(currentValue, (Map<String, Object>) value);
                currentConfig.put(key, currentValue);
            } else {
                currentConfig.put(key, value);
            }
        });
    }
    
    /**
     * 将配置写入文件
     */
    private void writeConfigToFile(Map<String, Object> config) throws IOException {
        String configPath = getConfigFilePath();
        Path path = Paths.get(configPath);
        
        // 确保父目录存在
        Files.createDirectories(path.getParent());
        
        ObjectMapper yamlMapper = new ObjectMapper(yamlFactory);
        String yamlContent = yamlMapper.writeValueAsString(config);
        Files.writeString(path, yamlContent);
        
        log.info("配置已更新并保存到文件: {}", configPath);
    }
    
    /**
     * 更新BotConfig对象
     */
    private void updateBotConfig(Map<String, Object> config) {
        Map<String, Object> botMap = (Map<String, Object>) config.get("bot");
        if (botMap != null) {
            botConfig.setOneOrEveryday((String) botMap.get("oneOrEveryday"));
            botConfig.setCreateId(((Number) botMap.get("createId")).longValue());
            
            Map<String, Object> messageMap = (Map<String, Object>) botMap.get("message");
            if (messageMap != null) {
                botConfig.getMessage().setConversation_timeout(((Number) messageMap.get("conversation_timeout")).intValue());
                botConfig.getMessage().setMax_context(((Number) messageMap.get("max_context")).intValue());
            }
            
            Map<String, Object> knowledgeMap = (Map<String, Object>) botMap.get("knowledge");
            if (knowledgeMap != null) {
                botConfig.getKnowledge().setDirectoryPath((String) knowledgeMap.get("directoryPath"));
                botConfig.getKnowledge().setFileTypes((String) knowledgeMap.get("fileTypes"));
                botConfig.getKnowledge().setRecursive((Boolean) knowledgeMap.get("recursive"));
                botConfig.getKnowledge().setUpdateInterval(((Number) knowledgeMap.get("updateInterval")).intValue());
                botConfig.getKnowledge().setChunkSize(((Number) knowledgeMap.get("chunkSize")).intValue());
                botConfig.getKnowledge().setChunkOverlap(((Number) knowledgeMap.get("chunkOverlap")).intValue());
                botConfig.getKnowledge().setTopK(((Number) knowledgeMap.get("topK")).intValue());
                botConfig.getKnowledge().setSimilarityThreshold(((Number) knowledgeMap.get("similarityThreshold")).doubleValue());
                botConfig.getKnowledge().setEmbeddingModel((String) knowledgeMap.get("embeddingModel"));
            }
        }
    }
    
    /**
     * 获取当前配置的副本
     */
    public Map<String, Object> getCurrentConfig() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("bot", createBotConfigMap());
        return configMap;
    }
}