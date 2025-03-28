package com.bot.aabot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库文本块实体类
 * 存储分割后的文本块及其向量表示
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextChunk {
    
    /**
     * 文本块内容
     */
    private String content;
    
    /**
     * 文本块在原文件中的起始位置
     */
    private int startPosition;
    
    /**
     * 文本块在原文件中的结束位置
     */
    private int endPosition;
    
    /**
     * 文本块的向量表示
     */
    private float[] embedding;
    
    /**
     * 创建不包含向量的文本块
     */
    public TextChunk(String content, int startPosition, int endPosition) {
        this.content = content;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }
} 