package com.bot.aabot.service;

import com.bot.aabot.config.KnowledgeBaseConfig;
import com.bot.aabot.entity.TextChunk;
import com.theokanning.openai.embedding.Embedding;
import com.theokanning.openai.embedding.EmbeddingRequest;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 向量化服务
 * 用于生成文本的向量表示
 * 优化版 - 提高向量化效率，减少内存使用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final KnowledgeBaseConfig config;
    
    @Value("${openai.api-key}")
    private String openaiApiKey;
    
    // 每批处理的最大大小
    private static final int MAX_BATCH_SIZE = 16;
    
    // API调用失败后的重试次数
    private static final int MAX_RETRIES = 3;
    
    // 重试等待时间（毫秒）
    private static final long RETRY_WAIT_MS = 1000;
    
    /**
     * 为单个文本生成向量表示
     *
     * @param text 要向量化的文本
     * @return 向量表示（浮点数数组）
     */
    public float[] createEmbedding(String text) {
        OpenAiService service = new OpenAiService(openaiApiKey);
        
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                EmbeddingRequest request = EmbeddingRequest.builder()
                        .model(config.getEmbeddingModel())
                        .input(List.of(text))
                        .build();
                
                List<Embedding> embeddings = service.createEmbeddings(request).getData();
                if (embeddings.isEmpty()) {
                    throw new RuntimeException("API返回了空的嵌入结果");
                }
                
                return toFloatArray(embeddings.get(0).getEmbedding());
            } catch (Exception e) {
                log.warn("获取文本嵌入时出错 (尝试 {}/{}): {}", attempt + 1, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(RETRY_WAIT_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    throw new RuntimeException("生成文本嵌入失败，已重试" + MAX_RETRIES + "次", e);
                }
            }
        }
        
        throw new RuntimeException("无法为文本生成嵌入");
    }
    
    /**
     * 为文本块批量生成向量表示
     * 优化版 - 考虑内存使用和API限制
     *
     * @param chunks 文本块列表
     */
    public void createEmbeddingsForChunks(List<TextChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        
        log.debug("为{}个文本块生成向量表示", chunks.size());
        
        // 限制批大小，避免API限制和内存问题
        int batchSize = Math.min(MAX_BATCH_SIZE, chunks.size());
        
        // 分批处理
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, chunks.size());
            processBatchWithRetry(chunks.subList(i, endIndex));
        }
    }
    
    /**
     * 处理一批文本块，带有重试逻辑
     */
    private void processBatchWithRetry(List<TextChunk> batchChunks) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                processBatch(batchChunks);
                return; // 成功处理，返回
            } catch (Exception e) {
                log.warn("批量处理文本嵌入时出错 (尝试 {}/{}): {}", attempt + 1, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        // 重试前等待一段时间
                        TimeUnit.MILLISECONDS.sleep(RETRY_WAIT_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    log.error("批量处理文本嵌入失败，已重试最大次数", e);
                    throw new RuntimeException("批量处理文本嵌入失败", e);
                }
            }
        }
    }
    
    /**
     * 处理一批文本块
     */
    private void processBatch(List<TextChunk> batchChunks) {
        List<String> texts = new ArrayList<>(batchChunks.size());
        for (TextChunk chunk : batchChunks) {
            texts.add(chunk.getContent());
        }
        
        OpenAiService service = new OpenAiService(openaiApiKey);
        
        log.debug("调用OpenAI API为{}个文本块生成嵌入", texts.size());
        
        EmbeddingRequest request = EmbeddingRequest.builder()
                .model(config.getEmbeddingModel())
                .input(texts)
                .build();
        
        List<Embedding> embeddings = service.createEmbeddings(request).getData();
        
        if (embeddings.size() != batchChunks.size()) {
            throw new RuntimeException("API返回的嵌入数量与请求不符，请求: " + 
                    batchChunks.size() + "，返回: " + embeddings.size());
        }
        
        for (int i = 0; i < batchChunks.size(); i++) {
            batchChunks.get(i).setEmbedding(toFloatArray(embeddings.get(i).getEmbedding()));
        }
        
        log.debug("成功为{}个文本块生成嵌入", batchChunks.size());
    }
    
    /**
     * 将List<Double>转换为float[]，节省内存
     */
    private float[] toFloatArray(List<Double> doubles) {
        float[] floats = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            floats[i] = doubles.get(i).floatValue();
        }
        return floats;
    }
    
    /**
     * 计算两个向量之间的余弦相似度
     *
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 余弦相似度，范围为[-1, 1]
     */
    public double cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException("向量维度不匹配: " + vec1.length + " vs " + vec2.length);
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        
        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
} 