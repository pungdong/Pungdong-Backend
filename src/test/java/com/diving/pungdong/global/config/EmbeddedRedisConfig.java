package com.diving.pungdong.global.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import redis.embedded.RedisServer;

/**
 * 테스트용 embedded Redis. <b>docker Redis(6379)와 분리된 고정 포트 {@value #TEST_REDIS_PORT}</b> 로
 * 띄워, 테스트가 로컬 dev 의 docker Redis 를 오염시키지 않게 한다. {@code application-test.yml} 의
 * {@code spring.redis.port} 도 같은 값이라, RedisProperties 바인딩 타이밍과 무관하게 항상 이 임베디드
 * 인스턴스를 쓴다.
 *
 * <p>등록 방식: {@code src/test/resources/META-INF/spring/
 * org.springframework.boot.autoconfigure.AutoConfiguration.imports} 에 FQCN 을 적어두면
 * {@code @SpringBootTest} 가 자동 로드. static 블록 1회(JVM 하나, ClassLoader 하나)라 모든 테스트
 * 컨텍스트가 같은 RedisServer 를 공유한다.
 *
 * <p>히스토리: 이전엔 {@code findFreePort()} 임의 포트 + {@code System.setProperty} 였는데, 이 static
 * 블록이 {@code RedisAutoConfiguration} 의 바인딩보다 <b>늦게</b> 적용돼 결국 yml 의 6379(=docker Redis)에
 * 붙어, 테스트(stub 프로필)가 docker Redis 에 stub venue 캐시를 누설했다(로컬 dev 빌더가 그걸 읽어 OFFICIAL
 * 위치가 stub 으로 보임). 고정 포트 + yml 일치로 그 타이밍 의존을 제거.
 */
@AutoConfiguration
public class EmbeddedRedisConfig {

    /** docker Redis(6379)와 안 겹치는 테스트 전용 포트 — {@code application-test.yml} 과 반드시 일치시킬 것. */
    public static final int TEST_REDIS_PORT = 16379;

    static {
        try {
            System.setProperty("spring.redis.port", String.valueOf(TEST_REDIS_PORT));
            RedisServer server = new RedisServer(TEST_REDIS_PORT);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                } catch (Exception ignored) {
                }
            }));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start embedded Redis on port " + TEST_REDIS_PORT, e);
        }
    }
}
