package com.diving.pungdong.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * 테스트용 embedded Redis. 클래스 로딩 시점에 OS 가 비어있는 포트를 선택해
 * {@code spring.redis.port} 시스템 프로퍼티에 박아두면, Spring 의 Redis
 * auto-config 이 그 포트로 LettuceConnectionFactory 를 만든다.
 * <p>
 * 등록 방식: {@code src/test/resources/META-INF/spring/
 * org.springframework.boot.autoconfigure.AutoConfiguration.imports} 에
 * FQCN 을 적어두면 {@code @SpringBootTest} 가 자동 로드. 개별 테스트의
 * {@code @Import} 가 필요 없음 (이전에는 필요했음).
 * <p>
 * 동기 (2026-05-19 Phase 0 deferred #2 후속): 이전에는 application-test.yml 의
 * 6379 고정 포트를 썼는데 Spring 의 컨텍스트 캐시가 분리되면 두 컨텍스트가
 * 동시에 6379 를 바인딩하려다 충돌. 임의 포트 + auto-config 으로 바꾸면
 * 모든 테스트 컨텍스트가 자동으로 같은 RedisServer (JVM 하나에 ClassLoader
 * 하나, 정적 블록 1회) 를 공유한다. 컨트롤러 테스트가 {@code @Import} 안 해도
 * 인증 필터가 의존하는 Redis 가 살아있음.
 */
@AutoConfiguration
public class EmbeddedRedisConfig {

    static {
        try {
            int port = findFreePort();
            // RedisProperties (`@ConfigurationProperties("spring.redis")`) 가 binding 될 때
            // system property 가 application-test.yml 의 값보다 우선이라 이 한 줄로 충분.
            System.setProperty("spring.redis.port", String.valueOf(port));
            RedisServer server = new RedisServer(port);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                } catch (Exception ignored) {
                }
            }));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start embedded Redis on a free port", e);
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
