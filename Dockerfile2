# 1. 빌드 단계
FROM gradle:7.6-jdk17 AS build
WORKDIR /app
COPY --chown=gradle:gradle . .
RUN gradle clean build -x test --no-daemon

# 2. 실행 단계
FROM openjdk:17-jdk-slim

WORKDIR /app


# 필요한 도구 설치 (telegraf 포함)
RUN apt-get update && \
    apt-get install -y tzdata sysstat curl gnupg procps && \
    ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone && \
    dpkg-reconfigure -f noninteractive tzdata && \
    curl -s https://repos.influxdata.com/influxdata-archive_compat.key | gpg --dearmor | tee /usr/share/keyrings/influxdata-archive-keyring.gpg >/dev/null && \
    echo "deb [signed-by=/usr/share/keyrings/influxdata-archive-keyring.gpg] https://repos.influxdata.com/debian stable main" | tee /etc/apt/sources.list.d/influxdata.list && \
    apt-get update && \
    apt-get install -y telegraf && \
    rm -rf /var/lib/apt/lists/*

# 로그 디렉토리 생성
RUN mkdir -p /app/logs && chmod -R 777 /app/logs

# JAR 복사
COPY --from=build /app/build/libs/*.jar app.jar

# 기본 실행: 자바 애플리케이션만
ENTRYPOINT ["java", "-Xmx512m", "-XX:NativeMemoryTracking=summary", "-jar", "app.jar"]
