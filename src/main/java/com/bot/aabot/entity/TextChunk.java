package com.bot.aabot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表示从知识库中检索到的文本块
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
     * 文本块来源路径
     */
    private String sourcePath;
    
    /**
     * 相关性得分
     */
    private float relevanceScore;
}