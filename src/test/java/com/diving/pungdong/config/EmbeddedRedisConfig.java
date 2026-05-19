package com.diving.pungdong.config;

import org.springframework.boot.test.context.TestConfiguration;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * 테스트용 embedded Redis. 클래스 로딩 시점에 OS 가 비어있는 포트를 선택해
 * {@code spring.redis.port} 시스템 프로퍼티에 박아두면, Spring 의 Redis
 * auto-config 이 그 포트로 LettuceConnectionFactory 를 만든다.
 * <p>
 * 동기 (2026-05-19 Phase 0 deferred #2): 이전에는 application-test.yml 의
 * 6379 고정 포트를 썼는데 Spring 의 컨텍스트 캐시가 분리되면 두 컨텍스트가
 * 동시에 6379 를 바인딩하려다 충돌. 임의 포트로 바꾸면 컨텍스트 캐시 머지를
 * 위한 hack ({@code @AutoConfigureRestDocs} + {@code RestDocsConfiguration}
 * import) 없이도 모든 테스트가 같은 RedisServer (JVM 하나에 ClassLoader 하나)
 * 를 공유한다.
 */
@TestConfiguration
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
