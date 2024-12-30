# 1. 빌드 단계: Gradle 이미지 사용
FROM gradle:7.6-jdk17 AS build

# 2. 작업 디렉토리 설정
WORKDIR /app

# 3. 필요한 파일 복사
COPY --chown=gradle:gradle . .

# 4. Gradle을 사용해 JAR 파일 빌드
RUN gradle clean build --no-daemon --stacktrace || (echo "Build failed. Check build logs for details." && tail -n 50 /app/build/reports/tests/test/index.html)

# 테스트 결과 복사
RUN cp -r build/docker /app/build || echo "No test reports available, build failed"

# 5. 실행 단계: 경량 JDK 이미지 사용
FROM openjdk:17-jdk-slim

# 6. 작업 디렉토리 설정
WORKDIR /app

# 7. 빌드한 JAR 파일 복사
COPY --from=build /app/build/libs/*.jar app.jar

# 8. 로그 디렉토리 만들기 및 권한 부여
RUN mkdir -p /app/logs && chmod -R 777 /app/logs

# 9. 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
