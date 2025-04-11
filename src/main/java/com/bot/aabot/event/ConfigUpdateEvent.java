package com.bot.aabot.event;

import org.springframework.context.ApplicationEvent;
import java.util.Map;

public class ConfigUpdateEvent extends ApplicationEvent {
    private final Map<String, Object> newConfig;
    
    public ConfigUpdateEvent(Object source, Map<String, Object> newConfig) {
        super(source);
        this.newConfig = newConfig;
    }
    
    public Map<String, Object> getNewConfig() {
        return newConfig;
    }
} 