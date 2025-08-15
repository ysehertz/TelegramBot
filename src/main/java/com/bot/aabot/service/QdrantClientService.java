package com.bot.aabot.service;

import com.bot.aabot.entity.TextChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Qdrant客户端服务，使用Spring AI与Qdrant知识库交互
 */
@Slf4j
@Service
public class QdrantClientService {

    @Value("${bot.knowledge.top-k}")
    private int topK;
    
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    
    public QdrantClientService(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        log.info("QdrantClientService已初始化，使用Spring AI VectorStore");
    }
    
    /**
     * 从Qdrant中搜索与查询最相似的文档
     */
    public List<TextChunk> searchSimilarDocuments(String query) {
        try {
            log.debug("开始在Qdrant中搜索与查询相似的文档: {}", query);
            
            // 使用Spring AI VectorStore进行相似性搜索
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(0.7)
                    .build();
            
            List<Document> searchResults = vectorStore.similaritySearch(searchRequest);
            
            if (searchResults.isEmpty()) {
                log.warn("VectorStore搜索结果为空");
                return Collections.emptyList();
            }
            
            // 将搜索结果转换为TextChunk对象
            List<TextChunk> chunks = searchResults.stream()
                    .map(this::convertDocumentToTextChunk)
                    .collect(Collectors.toList());
            
            log.info("VectorStore搜索完成，找到{}个相关文档", chunks.size());
            return chunks;
        } catch (Exception e) {
            log.error("从VectorStore搜索相似文档时出错: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 将Spring AI Document转换为TextChunk
     */
    private TextChunk convertDocumentToTextChunk(Document document) {
        TextChunk chunk = new TextChunk();
        chunk.setContent(document.getText());
        
        // 从元数据中提取源路径
        String sourcePath = document.getMetadata().getOrDefault("sourcePath", "").toString();
        if (sourcePath.isEmpty()) {
            sourcePath = document.getMetadata().getOrDefault("source", "").toString();
        }
        chunk.setSourcePath(sourcePath);
        
        // 设置相关性分数（如果可用）
        Object score = document.getMetadata().get("distance");
        if (score instanceof Number) {
            // 将距离转换为相似度分数（距离越小，相似度越高）
            chunk.setRelevanceScore(1.0f - ((Number) score).floatValue());
            } else {
            chunk.setRelevanceScore(0.8f); // 默认分数
        }
        
        return chunk;
    }
}