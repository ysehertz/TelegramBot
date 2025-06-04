package com.bot.aabot.service;

import com.bot.aabot.utils.LoggingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 重试服务
 * 实现max-retries配置功能
 */
@Service
public class RetryService {
    
    @Value("${bot.concurrency.max-retries:3}")
    private int maxRetries;
    
    @Value("${bot.concurrency.retry-delay:1000}")
    private long retryDelay; // 重试延迟，默认1秒
    
    @Value("${bot.concurrency.retry-backoff-multiplier:2}")
    private double backoffMultiplier; // 退避倍数，默认2倍
    
    /**
     * 执行带重试的操作
     * @param operation 要执行的操作
     * @param operationName 操作名称（用于日志）
     * @param <T> 返回类型
     * @return 操作结果
     */
    public <T> CompletableFuture<T> executeWithRetry(Supplier<T> operation, String operationName) {
        return executeWithRetryInternal(operation, operationName, 0, retryDelay);
    }
    
    /**
     * 执行带重试的操作（无返回值）
     * @param operation 要执行的操作
     * @param operationName 操作名称（用于日志）
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> executeWithRetry(Runnable operation, String operationName) {
        return executeWithRetryInternal(() -> {
            operation.run();
            return null;
        }, operationName, 0, retryDelay);
    }
    
    /**
     * 内部重试逻辑实现
     * @param operation 操作
     * @param operationName 操作名称
     * @param currentAttempt 当前尝试次数
     * @param currentDelay 当前延迟时间
     * @param <T> 返回类型
     * @return 操作结果
     */
    private <T> CompletableFuture<T> executeWithRetryInternal(
            Supplier<T> operation, 
            String operationName, 
            int currentAttempt, 
            long currentDelay) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                LoggingUtils.logOperation("RETRY_ATTEMPT", operationName, 
                    String.format("执行操作，尝试次数: %d/%d", currentAttempt + 1, maxRetries + 1));
                
                T result = operation.get();
                
                if (currentAttempt > 0) {
                    LoggingUtils.logOperation("RETRY_SUCCESS", operationName, 
                        String.format("操作成功，总尝试次数: %d", currentAttempt + 1));
                }
                
                return result;
                
            } catch (Exception e) {
                LoggingUtils.logError("RETRY_FAILED", 
                    String.format("操作 %s 第 %d 次尝试失败", operationName, currentAttempt + 1), e);
                
                if (currentAttempt >= maxRetries) {
                    LoggingUtils.logError("RETRY_EXHAUSTED", 
                        String.format("操作 %s 重试次数已耗尽，最终失败", operationName), e);
                    throw new RuntimeException("重试次数已耗尽", e);
                }
                
                // 计算下次重试的延迟时间（指数退避）
                long nextDelay = (long) (currentDelay * backoffMultiplier);
                
                LoggingUtils.logOperation("RETRY_SCHEDULE", operationName, 
                    String.format("将在 %d 毫秒后进行第 %d 次重试", nextDelay, currentAttempt + 2));
                
                try {
                    Thread.sleep(nextDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试被中断", ie);
                }
                
                // 递归调用进行重试
                return executeWithRetryInternal(operation, operationName, currentAttempt + 1, nextDelay).join();
            }
        });
    }
    
    /**
     * 执行带重试和熔断器的操作
     * @param operation 操作
     * @param operationName 操作名称
     * @param circuitBreakerService 熔断器服务
     * @param <T> 返回类型
     * @return 操作结果
     */
    public <T> CompletableFuture<T> executeWithRetryAndCircuitBreaker(
            Supplier<T> operation, 
            String operationName, 
            CircuitBreakerService circuitBreakerService) {
        
        // 检查熔断器状态
        if (!circuitBreakerService.isRequestAllowed(operationName)) {
            LoggingUtils.logError("CIRCUIT_BREAKER_REJECT", 
                String.format("熔断器阻止操作 %s 执行", operationName), 
                new Exception("Circuit breaker is open"));
            
            CompletableFuture<T> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("熔断器开启，拒绝执行"));
            return failedFuture;
        }
        
        return executeWithRetry(operation, operationName)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    circuitBreakerService.recordFailure(operationName, (Exception) throwable);
                } else {
                    circuitBreakerService.recordSuccess(operationName);
                }
            });
    }
    
    /**
     * 简单的重试方法（同步）
     * @param operation 操作
     * @param operationName 操作名称
     * @param <T> 返回类型
     * @return 操作结果
     * @throws Exception 最终失败时抛出异常
     */
    public <T> T executeWithRetrySynchronous(Supplier<T> operation, String operationName) throws Exception {
        Exception lastException = null;
        long currentDelay = retryDelay;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                LoggingUtils.logOperation("SYNC_RETRY_ATTEMPT", operationName, 
                    String.format("同步执行操作，尝试次数: %d/%d", attempt + 1, maxRetries + 1));
                
                T result = operation.get();
                
                if (attempt > 0) {
                    LoggingUtils.logOperation("SYNC_RETRY_SUCCESS", operationName, 
                        String.format("同步操作成功，总尝试次数: %d", attempt + 1));
                }
                
                return result;
                
            } catch (Exception e) {
                lastException = e;
                LoggingUtils.logError("SYNC_RETRY_FAILED", 
                    String.format("同步操作 %s 第 %d 次尝试失败", operationName, attempt + 1), e);
                
                if (attempt < maxRetries) {
                    try {
                        LoggingUtils.logOperation("SYNC_RETRY_DELAY", operationName, 
                            String.format("等待 %d 毫秒后重试", currentDelay));
                        Thread.sleep(currentDelay);
                        currentDelay = (long) (currentDelay * backoffMultiplier);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("重试被中断", ie);
                    }
                }
            }
        }
        
        LoggingUtils.logError("SYNC_RETRY_EXHAUSTED", 
            String.format("同步操作 %s 重试次数已耗尽", operationName), lastException);
        throw new Exception("重试次数已耗尽", lastException);
    }
} 