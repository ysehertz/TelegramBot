# 使用官方 JDK 17 镜像作为基础
FROM eclipse-temurin:17-jre-jammy
# 安装时区数据包（如果基础镜像已包含可跳过）
RUN apt-get update && \
    apt-get install -y tzdata && \
    rm -rf /var/lib/apt/lists/*

# 设置时区为美国纽约时区
ENV TZ=America/New_York
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo $TZ > /etc/timezone

# 设置工作目录（容器内的路径）
WORKDIR /app

# 将宿主机的 JAR 文件复制到容器的工作目录
COPY TgBot.jar /app/TgBot.jar

# 创建容器内的数据目录（包括日志和配置目录）
RUN mkdir -p /app/logs/Tgconfig

# 指定容器内的数据目录（包含日志和配置）
VOLUME /app/logs

# 暴露应用运行的端口（根据你的实际配置修改）
EXPOSE 8080

# 启动应用，指定配置路径
ENTRYPOINT ["java", "-jar", "TgBot.jar", "--bot.config.path=/app/logs/Tgconfig"] 