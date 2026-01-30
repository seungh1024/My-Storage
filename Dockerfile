# 1. 빌드 단계
FROM gradle:7.6-jdk17 AS build
WORKDIR /app
COPY --chown=gradle:gradle . .
RUN gradle clean build -x test --no-daemon

# 2. 실행 단계 (여기만 변경)
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update && \
    apt-get install -y tzdata sysstat curl gnupg procps && \
    ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone && \
    dpkg-reconfigure -f noninteractive tzdata && \
    curl -s https://repos.influxdata.com/influxdata-archive.key | gpg --dearmor \
      > /etc/apt/keyrings/influxdata-archive.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/influxdata-archive.gpg] https://repos.influxdata.com/debian stable main" \
      > /etc/apt/sources.list.d/influxdata.list && \
    apt-get update && \
    apt-get install -y telegraf && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/logs && chmod -R 777 /app/logs
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-Xmx512m", "-XX:NativeMemoryTracking=summary", "-jar", "app.jar"]
