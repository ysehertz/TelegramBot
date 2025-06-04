package com.bot.aabot.config;

import com.bot.aabot.utils.LoggingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步处理配置类
 * 配置Bot的异步处理线程池
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
public class AsyncConfig {

    @Value("${bot.async.core-pool-size:5}")
    private int corePoolSize;
    
    @Value("${bot.async.max-pool-size:20}")
    private int maxPoolSize;
    
    @Value("${bot.async.queue-capacity:500}")
    private int queueCapacity;
    
    @Value("${bot.async.thread-name-prefix:bot-async-}")
    private String threadNamePrefix;
    
    @Value("${bot.async.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    /**
     * 配置主要的异步执行器
     */
    @Bean(name = "botAsyncExecutor")
    public Executor botAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 基本配置
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        
        // 任务拒绝策略
        executor.setRejectedExecutionHandler(new BotRejectedExecutionHandler());
        
        // 线程池关闭等待
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        
        LoggingUtils.logSystemStatus(String.format(
            "异步线程池已初始化 - 核心线程数: %d, 最大线程数: %d, 队列容量: %d, 保活时间: %ds", 
            corePoolSize, maxPoolSize, queueCapacity, keepAliveSeconds));
        
        return executor;
    }
    
    /**
     * 配置消息处理专用的异步执行器
     */
    @Bean(name = "messageAsyncExecutor")
    public Executor messageAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 消息处理的特定配置
        executor.setCorePoolSize(Math.max(2, corePoolSize / 2));
        executor.setMaxPoolSize(Math.max(5, maxPoolSize / 2));
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("msg-async-");
        executor.setKeepAliveSeconds(30);
        
        executor.setRejectedExecutionHandler(new BotRejectedExecutionHandler());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        executor.initialize();
        
        LoggingUtils.logSystemStatus("消息处理异步线程池已初始化");
        
        return executor;
    }
    
    /**
     * 自定义拒绝策略
     */
    private static class BotRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            LoggingUtils.logError("THREAD_POOL_REJECTED", 
                String.format("线程池任务被拒绝 - 当前活跃线程: %d, 队列大小: %d, 最大线程数: %d", 
                    executor.getActiveCount(), 
                    executor.getQueue().size(), 
                    executor.getMaximumPoolSize()), 
                new Exception("Thread pool task rejected"));
                
            // 可以考虑将任务放入降级队列或者执行其他降级逻辑
            // 这里我们选择直接丢弃并记录日志
        }
    }
} 