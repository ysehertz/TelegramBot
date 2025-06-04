package com.bot.aabot.service;

import com.bot.aabot.utils.LoggingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 熔断器服务
 * 实现circuit-breaker-threshold配置功能
 */
@Service
public class CircuitBreakerService {
    
    @Value("${bot.concurrency.circuit-breaker-threshold:10}")
    private int circuitBreakerThreshold;
    
    @Value("${bot.concurrency.circuit-breaker-timeout:60000}")
    private long circuitBreakerTimeout; // 熔断恢复时间，默认60秒
    
    // 存储各个服务的失败计数
    private final ConcurrentHashMap<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();
    
    // 存储熔断器开启时间
    private final ConcurrentHashMap<String, AtomicLong> circuitOpenTime = new ConcurrentHashMap<>();
    
    // 熔断器状态
    private final ConcurrentHashMap<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    
    public enum CircuitState {
        CLOSED,    // 关闭状态，正常工作
        OPEN,      // 开启状态，拒绝请求
        HALF_OPEN  // 半开状态，尝试恢复
    }
    
    /**
     * 检查熔断器是否允许请求通过
     * @param serviceName 服务名称
     * @return 是否允许请求
     */
    public boolean isRequestAllowed(String serviceName) {
        CircuitState state = getCurrentState(serviceName);
        
        switch (state) {
            case CLOSED:
                return true;
                
            case OPEN:
                // 检查是否超过恢复时间
                if (isRecoveryTimeExceeded(serviceName)) {
                    circuitStates.put(serviceName, CircuitState.HALF_OPEN);
                    LoggingUtils.logOperation("CIRCUIT_BREAKER", serviceName, "熔断器进入半开状态");
                    return true;
                }
                return false;
                
            case HALF_OPEN:
                return true;
                
            default:
                return true;
        }
    }
    
    /**
     * 记录成功请求
     * @param serviceName 服务名称
     */
    public void recordSuccess(String serviceName) {
        CircuitState currentState = getCurrentState(serviceName);
        
        if (currentState == CircuitState.HALF_OPEN) {
            // 半开状态下成功，关闭熔断器
            circuitStates.put(serviceName, CircuitState.CLOSED);
            failureCounts.put(serviceName, new AtomicInteger(0));
            LoggingUtils.logOperation("CIRCUIT_BREAKER", serviceName, "熔断器恢复正常，状态变为关闭");
        } else if (currentState == CircuitState.CLOSED) {
            // 正常状态下成功，重置失败计数
            failureCounts.computeIfAbsent(serviceName, k -> new AtomicInteger(0)).set(0);
        }
    }
    
    /**
     * 记录失败请求
     * @param serviceName 服务名称
     * @param exception 异常信息
     */
    public void recordFailure(String serviceName, Exception exception) {
        AtomicInteger failures = failureCounts.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        int currentFailures = failures.incrementAndGet();
        
        LoggingUtils.logError("SERVICE_FAILURE", 
            String.format("服务 %s 失败，当前失败次数: %d/%d", serviceName, currentFailures, circuitBreakerThreshold), 
            exception);
        
        CircuitState currentState = getCurrentState(serviceName);
        
        if (currentState == CircuitState.HALF_OPEN) {
            // 半开状态下失败，重新开启熔断器
            openCircuit(serviceName);
        } else if (currentState == CircuitState.CLOSED && currentFailures >= circuitBreakerThreshold) {
            // 关闭状态下失败次数达到阈值，开启熔断器
            openCircuit(serviceName);
        }
    }
    
    /**
     * 开启熔断器
     * @param serviceName 服务名称
     */
    private void openCircuit(String serviceName) {
        circuitStates.put(serviceName, CircuitState.OPEN);
        circuitOpenTime.put(serviceName, new AtomicLong(System.currentTimeMillis()));
        
        LoggingUtils.logError("CIRCUIT_BREAKER_OPEN", 
            String.format("服务 %s 熔断器开启，失败次数达到阈值 %d", serviceName, circuitBreakerThreshold), 
            new Exception("Circuit breaker opened"));
    }
    
    /**
     * 获取当前熔断器状态
     * @param serviceName 服务名称
     * @return 熔断器状态
     */
    private CircuitState getCurrentState(String serviceName) {
        return circuitStates.computeIfAbsent(serviceName, k -> CircuitState.CLOSED);
    }
    
    /**
     * 检查是否超过恢复时间
     * @param serviceName 服务名称
     * @return 是否超过恢复时间
     */
    private boolean isRecoveryTimeExceeded(String serviceName) {
        AtomicLong openTime = circuitOpenTime.get(serviceName);
        if (openTime == null) {
            return false;
        }
        
        return System.currentTimeMillis() - openTime.get() > circuitBreakerTimeout;
    }
    
    /**
     * 获取服务状态信息
     * @param serviceName 服务名称
     * @return 状态信息
     */
    public String getServiceStatus(String serviceName) {
        CircuitState state = getCurrentState(serviceName);
        int failures = failureCounts.computeIfAbsent(serviceName, k -> new AtomicInteger(0)).get();
        
        return String.format("服务: %s, 状态: %s, 失败次数: %d/%d", 
            serviceName, state, failures, circuitBreakerThreshold);
    }
    
    /**
     * 重置服务状态
     * @param serviceName 服务名称
     */
    public void resetService(String serviceName) {
        circuitStates.put(serviceName, CircuitState.CLOSED);
        failureCounts.put(serviceName, new AtomicInteger(0));
        circuitOpenTime.remove(serviceName);
        
        LoggingUtils.logOperation("CIRCUIT_BREAKER_RESET", serviceName, "服务状态已重置");
    }
} 