package com.bot.aabot.service;

import com.bot.aabot.config.KnowledgeBaseConfig;
import com.bot.aabot.entity.TextChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本分块处理器
 * 用于将大型文本分割成语义连贯的小块
 * 优化版 - 减少内存占用、提高处理效率
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextChunkProcessor {

    private final KnowledgeBaseConfig config;
    
    // 预编译标点符号模式，提高搜索效率
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[.。!！?？\n]");

    /**
     * 将文本分割成大小适中的块
     * 优化的滑动窗口算法，支持批量处理
     *
     * @param text 要分割的原始文本
     * @return 分割后的文本块列表
     */
    public List<TextChunk> splitTextIntoChunks(String text) {
        List<TextChunk> chunks = new ArrayList<>();
        
        // 动态调整参数 - 针对大文本增加块大小，减少重叠
        int textLength = text.length();
        int chunkSize = calculateDynamicChunkSize(textLength);
        int chunkOverlap = calculateDynamicOverlap(textLength, chunkSize);
        
        log.debug("文本长度: {}, 动态块大小: {}, 动态重叠大小: {}", textLength, chunkSize, chunkOverlap);
        
        // 文本太短，直接返回一个块
        if (textLength <= chunkSize) {
            chunks.add(new TextChunk(text, 0, textLength));
            return chunks;
        }
        
        // 预先计算所有可能的分割点
        List<Integer> possibleSplitPositions = findAllPossibleSplitPositions(text);
        
        // 使用优化的滑动窗口进行分块
        int start = 0;
        while (start < textLength) {
            int end = Math.min(start + chunkSize, textLength);
            
            // 优化块的边界，使用预计算的分割点
            if (end < textLength) {
                int betterEnd = findOptimalSplitPosition(possibleSplitPositions, start, end);
                if (betterEnd > start) {
                    end = betterEnd;
                }
            }
            
            // 保证进度，防止死循环
            if (end <= start) {
                end = Math.min(start + chunkSize, textLength);
            }
            
            // 使用substring共享底层char数组
            String chunkContent = text.substring(start, end);
            chunks.add(new TextChunk(chunkContent, start, end));
            
            // 计算下一个块的起始位置，考虑重叠
            start = end - chunkOverlap;
            
            // 确保进度
            if (start >= textLength || end == textLength) {
                break;
            }
        }
        
        return chunks;
    }
    
    /**
     * 使用批处理方式分割大型文本，每批返回固定数量的文本块
     * 用于处理特别大的文本，避免OOM
     *
     * @param text 要分割的原始文本
     * @param batchSize 每批返回的文本块数量
     * @param batchProcessor 文本块批处理器
     */
    public void splitTextIntoChunksBatched(String text, int batchSize, TextChunkBatchProcessor batchProcessor) {
        int textLength = text.length();
        int chunkSize = calculateDynamicChunkSize(textLength);
        int chunkOverlap = calculateDynamicOverlap(textLength, chunkSize);
        
        log.debug("批处理模式: 文本长度: {}, 块大小: {}, 重叠大小: {}, 批大小: {}", 
                 textLength, chunkSize, chunkOverlap, batchSize);
        
        // 文本太短，直接处理一批
        if (textLength <= chunkSize) {
            List<TextChunk> batch = new ArrayList<>();
            batch.add(new TextChunk(text, 0, textLength));
            batchProcessor.processBatch(batch);
            return;
        }
        
        // 预先计算所有可能的分割点
        List<Integer> possibleSplitPositions = findAllPossibleSplitPositions(text);
        
        List<TextChunk> currentBatch = new ArrayList<>(batchSize);
        int start = 0;
        
        while (start < textLength) {
            int end = Math.min(start + chunkSize, textLength);
            
            // 优化块的边界
            if (end < textLength) {
                int betterEnd = findOptimalSplitPosition(possibleSplitPositions, start, end);
                if (betterEnd > start) {
                    end = betterEnd;
                }
            }
            
            // 保证进度
            if (end <= start) {
                end = Math.min(start + chunkSize, textLength);
            }
            
            // 添加到当前批次
            currentBatch.add(new TextChunk(text.substring(start, end), start, end));
            
            // 如果当前批次已满，处理并清空
            if (currentBatch.size() >= batchSize) {
                batchProcessor.processBatch(currentBatch);
                currentBatch = new ArrayList<>(batchSize);
            }
            
            // 计算下一个块的起始位置
            start = end - chunkOverlap;
            
            // 确保进度
            if (start >= textLength || end == textLength) {
                break;
            }
        }
        
        // 处理最后一批（如果有）
        if (!currentBatch.isEmpty()) {
            batchProcessor.processBatch(currentBatch);
        }
    }
    
    /**
     * 查找文本中所有可能的分割点（标点符号位置）
     */
    private List<Integer> findAllPossibleSplitPositions(String text) {
        List<Integer> positions = new ArrayList<>();
        Matcher matcher = PUNCTUATION_PATTERN.matcher(text);
        
        while (matcher.find()) {
            positions.add(matcher.start());
        }
        
        return positions;
    }
    
    /**
     * 在指定范围内找到最优的分割位置
     */
    private int findOptimalSplitPosition(List<Integer> positions, int start, int end) {
        // 找到最接近end但不超过end的位置
        int optimalPosition = start;
        
        for (int pos : positions) {
            if (pos >= start && pos < end && pos > optimalPosition) {
                optimalPosition = pos;
            }
        }
        
        // 如果找到了有效位置，返回标点后的位置
        return optimalPosition > start ? optimalPosition + 1 : optimalPosition;
    }
    
    /**
     * 根据文本总长度动态计算chunk大小
     */
    private int calculateDynamicChunkSize(int textLength) {
        int baseChunkSize = config.getChunkSize();
        
        // 对于非常大的文本，增加块大小以减少块数量
        if (textLength > 100000) { // 10万字符以上
            return Math.min(baseChunkSize * 2, 2000); // 最大2000字符
        } else if (textLength > 50000) { // 5万字符以上
            return Math.min(baseChunkSize * 3 / 2, 1500); // 最大1500字符
        }
        
        return baseChunkSize;
    }
    
    /**
     * 根据文本总长度动态计算重叠大小
     */
    private int calculateDynamicOverlap(int textLength, int chunkSize) {
        int baseOverlap = config.getChunkOverlap();
        
        // 对于大文本，减少重叠比例以节省内存
        if (textLength > 100000) { // 10万字符以上
            return Math.min(baseOverlap / 2, 50); // 最小50字符
        } else if (textLength > 50000) { // 5万字符以上
            return Math.min(baseOverlap * 2 / 3, 75); // 最小75字符
        }
        
        return baseOverlap;
    }
    
    /**
     * 文本块批处理接口
     */
    public interface TextChunkBatchProcessor {
        void processBatch(List<TextChunk> batchChunks);
    }
} 