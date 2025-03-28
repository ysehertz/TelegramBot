package com.bot.aabot.service;

import com.bot.aabot.config.KnowledgeBaseConfig;
import com.bot.aabot.entity.TextChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 向量存储服务
 * 用于存储和检索文本块及其向量
 * 优化版 - 减少内存占用，提高搜索效率
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final KnowledgeBaseConfig config;
    private final EmbeddingService embeddingService;
    
    // 内存中存储文本块及其向量，使用ConcurrentHashMap提高并发性能
    private final Map<String, TextChunk> chunks = new ConcurrentHashMap<>();
    
    // 记录添加的块数
    private final AtomicInteger chunkCounter = new AtomicInteger(0);
    
    /**
     * 添加或更新向量存储中的文本块
     *
     * @param textChunks 文本块列表
     */
    public void upsertChunks(List<TextChunk> textChunks) {
        if (textChunks.isEmpty()) {
            return;
        }
        
        int addedCount = 0;
        int updatedCount = 0;
        
        for (TextChunk chunk : textChunks) {
            // 确保文本块有向量表示
            if (chunk.getEmbedding() == null) {
                log.warn("文本块没有向量表示，跳过: {}", chunk.getContent().substring(0, Math.min(50, chunk.getContent().length())));
                continue;
            }
            
            // 使用内容的哈希值作为键
            String key = getChunkKey(chunk);
            if (chunks.containsKey(key)) {
                updatedCount++;
            } else {
                addedCount++;
            }
            chunks.put(key, chunk);
        }
        
        int totalChunks = chunkCounter.addAndGet(addedCount);
        log.debug("更新向量存储: 新增{}个块, 更新{}个块, 当前总数{}", addedCount, updatedCount, totalChunks);
    }
    
    /**
     * 清空向量存储
     */
    public void clearStore() {
        int previousSize = chunks.size();
        chunks.clear();
        chunkCounter.set(0);
        log.info("清空向量存储，移除了{}个文本块", previousSize);
    }
    
    /**
     * 根据查询文本检索相似度最高的文本块
     * 优化版 - 提高搜索效率，减少资源占用
     *
     * @param query 查询文本
     * @return 按相似度排序的文本块列表
     */
    public List<TextChunk> similaritySearch(String query) {
        if (chunks.isEmpty()) {
            log.warn("向量存储为空，无法执行相似度搜索");
            return Collections.emptyList();
        }
        
        long startTime = System.currentTimeMillis();
        
        // 生成查询向量
        float[] queryEmbedding;
        try {
            queryEmbedding = embeddingService.createEmbedding(query);
        } catch (Exception e) {
            log.error("生成查询向量时出错", e);
            return Collections.emptyList();
        }
        
        double similarityThreshold = config.getSimilarityThreshold();
        int topK = config.getTopK();
        
        // 使用优先队列存储结果
        // 使用最小堆，这样可以始终保留相似度最高的topK个结果
        PriorityQueue<Map.Entry<Double, TextChunk>> topResults = 
                new PriorityQueue<>(topK, Map.Entry.comparingByKey());
        
        int processed = 0;
        for (TextChunk chunk : chunks.values()) {
            processed++;
            double similarity = embeddingService.cosineSimilarity(queryEmbedding, chunk.getEmbedding());
            
            if (similarity >= similarityThreshold) {
                // 如果队列未满，直接添加
                if (topResults.size() < topK) {
                    topResults.add(new AbstractMap.SimpleEntry<>(similarity, chunk));
                } 
                // 如果当前相似度比队列中最小的大，则替换
                else if (topResults.peek().getKey() < similarity) {
                    topResults.poll();
                    topResults.add(new AbstractMap.SimpleEntry<>(similarity, chunk));
                }
            }
        }
        
        // 从优先队列转换为按相似度降序排序的列表
        List<TextChunk> result = new ArrayList<>(topResults.size());
        while (!topResults.isEmpty()) {
            result.add(0, topResults.poll().getValue()); // 插入到开头，实现降序
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.debug("相似度搜索完成: 处理了{}个文本块, 返回{}个结果, 耗时{}ms", 
                processed, result.size(), duration);
        
        return result;
    }
    
    /**
     * 获取文本块的唯一键
     */
    private String getChunkKey(TextChunk chunk) {
        return String.valueOf(chunk.getContent().hashCode());
    }
    
    /**
     * 获取向量存储中的文本块数量
     */
    public int getChunkCount() {
        return chunks.size();
    }
    
    /**
     * 检查向量存储是否为空
     */
    public boolean isEmpty() {
        return chunks.isEmpty();
    }
} 