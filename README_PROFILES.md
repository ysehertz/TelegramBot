# 多环境配置使用指南

## 环境配置说明

本项目支持多环境配置，目前包括以下环境：

- `dev`: 开发环境
- `prod`: 生产环境

## 如何切换环境

### 方法1: 通过环境变量

在启动应用时设置环境变量 `SPRING_PROFILES_ACTIVE`：

```bash
# Windows 开发环境
set SPRING_PROFILES_ACTIVE=dev
java -jar aaBot.jar

# Linux/Mac 开发环境
export SPRING_PROFILES_ACTIVE=dev
java -jar aaBot.jar

# 生产环境
export SPRING_PROFILES_ACTIVE=prod
java -jar aaBot.jar
```

### 方法2: 通过JVM参数

在启动应用时通过JVM参数指定环境：

```bash
# 开发环境
java -Dspring.profiles.active=dev -jar aaBot.jar

# 生产环境
java -Dspring.profiles.active=prod -jar aaBot.jar
```

### 方法3: 在Docker中使用

在Docker中运行时，可以通过环境变量设置：

```bash
# 开发环境
docker run -e SPRING_PROFILES_ACTIVE=dev -d -v /path/to/logs:/app/logs --name TgBot tg-bot:1

# 生产环境
docker run -e SPRING_PROFILES_ACTIVE=prod -d -v /path/to/logs:/app/logs --name TgBot tg-bot:1
```

## 配置文件说明

环境相关的配置文件：

- `application-dev.yml`: 开发环境配置
- `application-prod.yml`: 生产环境配置
- `bot-config-dev.yml`: 开发环境机器人配置
- `bot-config-prod.yml`: 生产环境机器人配置

主配置文件 `application.yml` 会根据当前激活的环境自动加载对应的配置文件。

## 默认环境

如果未指定环境，系统默认使用开发环境（`dev`）。
