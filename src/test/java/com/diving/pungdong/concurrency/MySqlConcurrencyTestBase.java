package com.diving.pungdong.concurrency;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실 MySQL 동시성 테스트 base — H2 가 {@code SELECT ... FOR UPDATE}·REPEATABLE READ 스냅샷의 동시성 의미를 제대로
 * 재현 못 해(락 타임아웃/가시성 차이) 비관 락으로 막는 이중환불·좌석 overbooking 을 검증할 수 없다. Testcontainers 로
 * 실제 MySQL 8.4 를 띄우고 Flyway 마이그레이션(V1~)까지 돌려 <b>prod 와 같은 스키마·엔진</b>에서 race 를 재현한다.
 *
 * <p><b>싱글톤 컨테이너</b>: {@code @Container}/{@code @Testcontainers}(클래스마다 start/stop) 대신 static 블록에서
 * <b>JVM 당 한 번</b> 띄우고 멈추지 않는다. 이유 — Spring 컨텍스트 캐시는 첫 컨테이너의 포트를 물고 재사용되는데,
 * 클래스마다 컨테이너를 재시작하면 두 번째 테스트 클래스에서 <b>캐시된 컨텍스트가 이미 멈춘 컨테이너</b>를 가리켜
 * "Connection refused" 가 난다. 하나를 띄워 모든 동시성 테스트가 같은 포트를 공유하면 컨텍스트 캐시가 유효하다.
 * (JVM 종료 시 Testcontainers shutdown hook 이 정리 — OrbStack 에서 Ryuk 은 build.gradle 에서 끈다.)
 *
 * <p>{@code @ActiveProfiles("test")} 는 유지(stub PG·embedded redis 격리 그대로)하되 DB 만 {@link DynamicPropertySource}
 * 로 오버라이드 — H2 대신 Testcontainers MySQL, Flyway ON, {@code hbm2ddl=validate}(운영과 동일).
 */
@SpringBootTest
@ActiveProfiles("test")
// @Container 는 붙이지 않는다(생명주기는 아래 싱글톤 static start 가 소유) — @Testcontainers 는 오직
// disabledWithoutDocker 조건만 쓴다: Docker 없는 환경(로컬 hermetic 실행)에선 이 클래스만 skip(실패 아님).
@Testcontainers(disabledWithoutDocker = true)
// 기본 `test`(hermetic H2)에서 제외하고 별도 `mysqlTest`(Docker) 태스크로만 돈다 — build.gradle 의 태그 필터.
// 왜: 이 MySQL 컨텍스트를 H2 스위트와 같은 JVM 에 섞으면 Spring 컨텍스트 캐시가 한도를 넘어 축출되고, 축출된
// H2 컨텍스트의 create-drop 종료가 공유 mem DB(testdb) 스키마를 지워 무관한 H2 테스트가 "Table not found" 로
// 깨진다(testing.md 의 hermetic 원칙과도 배치). 태스크를 갈라 JVM·컨텍스트 캐시를 분리한다.
@Tag("mysql")
public abstract class MySqlConcurrencyTestBase {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pungdong")
            .withUsername("pungdong")
            .withPassword("pungdongpw");

    static {
        MYSQL.start(); // JVM 당 1회. 멈추지 않는다(위 Javadoc — 컨텍스트 캐시 유효성).
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.hikari.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // H2 프로파일이 끈 Flyway 를 켜고(실 마이그레이션으로 스키마 생성) 운영과 동일하게 validate.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQL8Dialect");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.auto", () -> "validate");
    }
}
