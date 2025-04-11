package com.bot.aabot.service;

import com.bot.aabot.entity.TextChunk;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.theokanning.openai.embedding.EmbeddingRequest;
import com.theokanning.openai.embedding.EmbeddingResult;
import com.theokanning.openai.service.OpenAiService;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Qdrant客户端服务，使用官方Java客户端与Qdrant云端知识库交互
 */
@Slf4j
@Service
public class QdrantClientService {

    @Value("${openai.api-key}")
    private String openaiApiKey;

    @Value("${bot.knowledge.qdrant.url}")
    private String qdrantUrl;
    
    @Value("${bot.knowledge.qdrant.api-key}")
    private String qdrantApiKey;

    @Value("${bot.knowledge.qdrant.collection-name}")
    private String collectionName;

    @Value("${bot.knowledge.embedding-model}")
    private String embeddingModel;

    @Value("${bot.knowledge.top-k}")
    private int topK;
    
    private QdrantClient qdrantClient;
    
    @PostConstruct
    public void init() {
        try {
            // 从URL中提取主机名（去除协议前缀）
            String host = qdrantUrl.replace("https://", "").replace("http://", "");
            
            // 创建Qdrant客户端
            // 注意：Qdrant使用gRPC连接的默认端口是6334
            qdrantClient = new QdrantClient(
                QdrantGrpcClient.newBuilder(
                    host,  // 主机名
                    6334,  // gRPC端口
                    true   // 使用HTTPS
                )
                .withApiKey(qdrantApiKey)  // 设置API密钥
                .build()
            );
            
            log.info("Qdrant客户端已初始化，连接到: {}", qdrantUrl);
        } catch (Exception e) {
            log.error("初始化Qdrant客户端失败", e);
            throw new RuntimeException("无法连接到Qdrant服务", e);
        }
    }
    
    @PreDestroy
    public void cleanup() {
        try {
            if (qdrantClient != null) {
                qdrantClient.close();
                log.info("Qdrant客户端已关闭");
            }
        } catch (Exception e) {
            log.error("关闭Qdrant客户端时出错", e);
        }
    }
    
    /**
     * 从Qdrant中搜索与查询最相似的文档
     */
    public List<TextChunk> searchSimilarDocuments(String query) {
        try {
            log.debug("开始在Qdrant中搜索与查询相似的文档: {}", query);
            
            // 1. 使用OpenAI API生成查询的嵌入向量
            List<Float> queryEmbedding = generateEmbedding(query);
            if (queryEmbedding.isEmpty()) {
                log.error("生成查询的嵌入向量失败");
                return Collections.emptyList();
            }
            
            // 2. 构建搜索请求
            SearchPoints.Builder searchRequest = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .setLimit(topK);

            Points.WithPayloadSelector withPayload = Points.WithPayloadSelector.newBuilder()
                    .setEnable(true)  // 包含所有payload字段
                    .build();
            searchRequest.setWithPayload(withPayload);

            // 添加查询向量
            for (Float value : queryEmbedding) {
                searchRequest.addVector(value);
            }
            
            // 3. 执行搜索
            List<ScoredPoint> searchResults = qdrantClient.searchAsync(searchRequest.build()).get();
            
            if (searchResults.isEmpty()) {
                log.warn("Qdrant搜索结果为空");
                return Collections.emptyList();
            }
            System.out.println("searchResults = " + searchResults);
            // 4. 将搜索结果转换为TextChunk对象
            List<TextChunk> chunks = new ArrayList<>();
            for (ScoredPoint point : searchResults) {
                TextChunk chunk = new TextChunk();
                Map<String, JsonWithInt.Value> payloadMap = point.getPayloadMap();
                chunk.setContent(payloadMap.get("content").getStringValue());
                chunk.setSourcePath(payloadMap.get("sourcePath").getStringValue());
                chunk.setRelevanceScore(point.getScore());
                chunks.add(chunk);
            }
            
            log.info("Qdrant搜索完成，找到{}个相关文档", chunks.size());
            return chunks;
        } catch (ExecutionException | InterruptedException e) {
            log.error("从Qdrant搜索相似文档时出错: {}", e.getMessage(), e);
            Thread.currentThread().interrupt(); // 重置中断状态
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("从Qdrant搜索相似文档时出错: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 使用OpenAI API生成文本的嵌入向量
     */
    private List<Float> generateEmbedding(String text) {
        try {
            OpenAiService service = new OpenAiService(openaiApiKey, Duration.ofSeconds(60));
            
            EmbeddingRequest request = EmbeddingRequest.builder()
                    .model(embeddingModel)
                    .input(Collections.singletonList(text))
                    .build();
            
            EmbeddingResult result = service.createEmbeddings(request);
            
            if (result.getData() != null && !result.getData().isEmpty()) {
                return result.getData().get(0).getEmbedding().stream()
                        .map(Double::floatValue)
                        .collect(Collectors.toList());
            } else {
                log.error("OpenAI返回的嵌入结果为空");
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("生成嵌入向量时出错: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}