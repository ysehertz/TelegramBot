package com.bot.aabot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@ConfigurationProperties()
@PropertySource(value = "classpath:bot-config-${spring.profiles.active:dev}.yml", factory = YamlPropertySourceFactory.class)
public class ConfigProperties {
    
    @Autowired
    private Environment environment;
    
    @Value("${bot.config.path:}")
    private String externalConfigPath;
    
    // 这个类用于确保Spring Boot能够正确处理YAML配置文件的加载和更新
    // 如果指定了外部配置文件路径，会优先使用外部配置
} 