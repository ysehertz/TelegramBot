package com.bot.aabot.controller;

import com.bot.aabot.config.KnowledgeBaseConfig;
import com.bot.aabot.service.KnowledgeLoaderService;
import com.bot.aabot.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库控制器
 * 提供知识库管理相关的API接口
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeLoaderService knowledgeLoaderService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeBaseConfig knowledgeBaseConfig;
    
    /**
     * 手动重新加载知识库
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reloadKnowledgeBase() {
        knowledgeLoaderService.forceReload();
        
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "知识库已重新加载");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取知识库状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getKnowledgeBaseStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("chunkCount", vectorStoreService.getChunkCount());
        status.put("isEmpty", vectorStoreService.isEmpty());
        status.put("directoryPath", knowledgeBaseConfig.getKnowledgeDirectoryPath());
        status.put("supportedFileTypes", knowledgeBaseConfig.getSupportedFileTypes());
        status.put("recursive", knowledgeBaseConfig.isRecursive());
        status.put("updateInterval", knowledgeBaseConfig.getUpdateInterval());
        
        // 检查目录是否存在
        Path directoryPath = Paths.get(knowledgeBaseConfig.getKnowledgeDirectoryPath());
        status.put("directoryExists", Files.exists(directoryPath) && Files.isDirectory(directoryPath));
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * 更新支持的文件类型
     */
    @PostMapping("/file-types")
    public ResponseEntity<Map<String, Object>> updateFileTypes(@RequestParam String fileTypes) {
        Map<String, Object> response = new HashMap<>();
        
        // 更新文件类型配置
        knowledgeBaseConfig.setFileTypesString(fileTypes);
        
        response.put("status", "success");
        response.put("message", "文件类型已更新");
        response.put("newFileTypes", knowledgeBaseConfig.getSupportedFileTypes());
        
        // 触发知识库重新加载
        knowledgeLoaderService.forceReload();
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 更新知识库目录路径
     */
    @PostMapping("/directory")
    public ResponseEntity<Map<String, Object>> updateDirectoryPath(@RequestParam String directoryPath) {
        Map<String, Object> response = new HashMap<>();
        
        // 检查目录是否存在
        Path newPath = Paths.get(directoryPath);
        if (!Files.exists(newPath) || !Files.isDirectory(newPath)) {
            response.put("status", "error");
            response.put("message", "指定的路径不存在或不是文件夹");
            return ResponseEntity.badRequest().body(response);
        }
        
        // 更新目录路径配置
        knowledgeBaseConfig.setKnowledgeDirectoryPath(directoryPath);
        
        response.put("status", "success");
        response.put("message", "知识库目录已更新");
        response.put("directoryPath", directoryPath);
        
        // 触发知识库重新加载
        knowledgeLoaderService.forceReload();
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 更新是否递归搜索子文件夹的配置
     */
    @PostMapping("/recursive")
    public ResponseEntity<Map<String, Object>> updateRecursiveSearch(@RequestParam boolean recursive) {
        Map<String, Object> response = new HashMap<>();
        
        // 更新递归搜索配置
        knowledgeBaseConfig.setRecursive(recursive);
        
        response.put("status", "success");
        response.put("message", "递归搜索配置已更新");
        response.put("recursive", recursive);
        
        // 触发知识库重新加载
        knowledgeLoaderService.forceReload();
        
        return ResponseEntity.ok(response);
    }
}

 