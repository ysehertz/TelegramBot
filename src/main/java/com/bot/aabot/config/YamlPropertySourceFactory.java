package com.bot.aabot.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.Properties;

public class YamlPropertySourceFactory implements PropertySourceFactory {
    
    @Value("${bot.config.path:}")
    private String externalConfigPath;
    
    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource encodedResource) throws IOException {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        
        // 如果指定了外部配置文件路径，优先使用外部配置
        if (externalConfigPath != null && !externalConfigPath.isEmpty()) {
            factory.setResources(new FileSystemResource(externalConfigPath + "/bot-config.yml"));
        } else {
            factory.setResources(encodedResource.getResource());
        }
        
        factory.afterPropertiesSet();
        Properties properties = factory.getObject();

        return new PropertiesPropertySource(
            encodedResource.getResource().getFilename(), properties
        );
    }
} 