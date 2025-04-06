package com.bot.aabot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@ConfigurationProperties()
@PropertySource(value = "classpath:bot-config.yml", factory = YamlPropertySourceFactory.class)
public class ConfigProperties {
    // 这个类用于确保Spring Boot能够正确处理YAML配置文件的加载和更新
} 