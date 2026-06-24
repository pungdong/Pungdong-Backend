# syntax=docker/dockerfile:1

############################################################
# 1) Build — Gradle + JDK 17 (멀티스테이지 빌더)
############################################################
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# 의존성 레이어 캐시: 빌드 스크립트/래퍼만 먼저 복사 후 워밍 (소스 변경이 deps 캐시를 깨지 않게).
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true

# 소스 복사 후 실행 가능 jar 빌드.
# 테스트/문서(asciidoctor)는 CI(.github/workflows/ci.yml)가 책임 → 이미지 빌드는 "패키징만"
# (-x test -x asciidoctor). 이렇게 하면 배포가 테스트 실행/플랫폼(embedded-redis 등)에 묶이지 않는다.
# 트레이드오프: 이 이미지에는 REST Docs /docs HTML 미포함(스테이징 비핵심, 후속 복원).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test -x asciidoctor \
    && cp build/libs/pungdong-*.jar /workspace/app.jar

############################################################
# 2) Runtime — JRE only (작은 실행 이미지)
############################################################
FROM eclipse-temurin:17-jre-jammy AS runtime

# 헬스체크용 curl + 비루트 유저 (보안: root 로 실행하지 않음).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --home /app app

WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app

EXPOSE 8080

# 컨테이너에 할당된 메모리에 맞춰 힙 자동 조정. 런타임 옵션은 JAVA_OPTS 로 덮어쓸 수 있음.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# actuator 헬스. start-period 를 넉넉히(콜드스타트 + DB/Redis 연결 대기).
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# exec 로 PID 1 = JVM → SIGTERM(ECS stop)이 JVM 으로 바로 전달돼 graceful shutdown.
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
