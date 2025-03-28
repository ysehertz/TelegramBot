package com.bot.aabot.service;

import com.bot.aabot.config.KnowledgeBaseConfig;
import com.bot.aabot.entity.TextChunk;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识库加载器服务
 * 用于定期从文件夹加载知识库并更新向量存储
 * 优化版 - 使用批处理方式处理大文件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeLoaderService {

    private final KnowledgeBaseConfig config;
    private final TextChunkProcessor textChunkProcessor;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    
    // 存储文件路径及其最后修改时间
    private final Map<Path, FileTime> fileLastModifiedTimes = new HashMap<>();
    
    // 批处理大小
    private static final int BATCH_SIZE = 20;
    
    /**
     * 应用启动时加载知识库
     */
    @PostConstruct
    public void init() {
        log.info("初始化知识库加载器");
        loadKnowledgeBase();
    }
    
    /**
     * 定期执行知识库更新任务
     */
    @Scheduled(fixedDelayString = "${bot.knowledge.update-interval:60}000")
    public void scheduledKnowledgeBaseUpdate() {
        log.info("执行定时知识库更新检查");
        loadKnowledgeBase();
    }
    
    /**
     * 从文件夹加载知识库内容并更新向量存储
     */
    public synchronized void loadKnowledgeBase() {
        Path directoryPath = Paths.get(config.getKnowledgeDirectoryPath());
        
        try {
            if (!Files.exists(directoryPath)) {
                log.warn("知识库文件夹不存在: {}", directoryPath);
                return;
            }
            
            if (!Files.isDirectory(directoryPath)) {
                log.warn("指定的路径不是文件夹: {}", directoryPath);
                return;
            }
            
            log.info("开始扫描知识库文件夹: {}", directoryPath);
            
            // 获取所有支持的文件
            List<Path> files = findSupportedFiles(directoryPath);
            log.info("找到{}个支持的文件", files.size());
            
            // 检查文件变更
            boolean hasChanges = checkFilesForChanges(files);
            
            if (!hasChanges) {
                log.info("所有文件均未变化，跳过更新");
                return;
            }
            
            log.info("检测到文件变化，开始更新知识库");
            
            // 清空现有向量存储
            vectorStoreService.clearStore();
            
            // 处理每个文件
            for (Path file : files) {
                processFileBatched(file);
            }
            
            log.info("知识库更新完成，当前存储块数: {}", vectorStoreService.getChunkCount());
            
        } catch (Exception e) {
            log.error("加载知识库时发生错误", e);
        }
    }
    
    /**
     * 查找所有支持的文件
     */
    private List<Path> findSupportedFiles(Path directoryPath) throws IOException {
        List<String> supportedTypes = config.getSupportedFileTypes();
        boolean isRecursive = config.isRecursive();
        
        try (Stream<Path> pathStream = isRecursive 
                ? Files.walk(directoryPath) 
                : Files.list(directoryPath)) {
            
            return pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return supportedTypes.stream().anyMatch(fileName::endsWith);
                    })
                    .collect(Collectors.toList());
        }
    }
    
    /**
     * 检查文件是否有变化
     * @return 如果有任何文件变化，返回true
     */
    private boolean checkFilesForChanges(List<Path> currentFiles) throws IOException {
        boolean hasChanges = false;
        Map<Path, FileTime> newFileModifiedTimes = new HashMap<>();
        
        // 检查新增和修改的文件
        for (Path file : currentFiles) {
            FileTime currentModifiedTime = Files.getLastModifiedTime(file);
            newFileModifiedTimes.put(file, currentModifiedTime);
            
            FileTime previousModifiedTime = fileLastModifiedTimes.get(file);
            if (previousModifiedTime == null || !previousModifiedTime.equals(currentModifiedTime)) {
                log.info("文件已修改或新增: {}", file);
                hasChanges = true;
            }
        }
        
        // 检查删除的文件
        if (fileLastModifiedTimes.size() != newFileModifiedTimes.size()) {
            Set<Path> removedFiles = new HashSet<>(fileLastModifiedTimes.keySet());
            removedFiles.removeAll(newFileModifiedTimes.keySet());
            
            if (!removedFiles.isEmpty()) {
                log.info("检测到已删除的文件: {}", removedFiles);
                hasChanges = true;
            }
        }
        
        // 更新文件修改时间记录
        fileLastModifiedTimes.clear();
        fileLastModifiedTimes.putAll(newFileModifiedTimes);
        
        return hasChanges;
    }
    
    /**
     * 使用批处理方式处理单个文件
     */
    private void processFileBatched(Path filePath) {
        try {
            String fileName = filePath.getFileName().toString();
            log.info("开始处理文件: {}", filePath);
            
            // 获取文件大小
            long fileSize = Files.size(filePath);
            log.info("文件[{}]大小: {} 字节", fileName, fileSize);
            
            // 读取文件内容
            String content = Files.readString(filePath);
            log.info("文件[{}]已读取，总字符数: {}", fileName, content.length());
            
            // 为每个文本块添加文件名前缀
            content = "文件: " + fileName + "\n\n" + content;
            
            // 使用批处理方式分割文本并处理
            AtomicInteger processedChunks = new AtomicInteger(0);
            
            textChunkProcessor.splitTextIntoChunksBatched(content, BATCH_SIZE, chunks -> {
                try {
                    // 为当前批次的文本块生成向量
                    embeddingService.createEmbeddingsForChunks(chunks);
                    
                    // 将当前批次的文本块添加到向量存储
                    vectorStoreService.upsertChunks(chunks);
                    
                    int currentCount = processedChunks.addAndGet(chunks.size());
                    log.info("文件[{}]已处理{}个文本块", fileName, currentCount);
                    
                    // 帮助JVM垃圾回收
                    chunks.clear();
                } catch (Exception e) {
                    log.error("处理文件[{}]的批次时出错", fileName, e);
                }
            });
            
            log.info("文件[{}]处理完成, 共生成{}个文本块", fileName, processedChunks.get());
            
            // 主动触发垃圾回收，释放内存
            if (fileSize > 1024 * 1024) { // 对于大于1MB的文件
                System.gc();
            }
            
        } catch (Exception e) {
            log.error("处理文件[{}]时发生错误", filePath, e);
        }
    }
    
    /**
     * 强制重新加载知识库
     */
    public void forceReload() {
        log.info("强制重新加载知识库");
        fileLastModifiedTimes.clear();
        loadKnowledgeBase();
    }
} 