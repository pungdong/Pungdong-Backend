# Redis (인프라)

> 도메인이 아니라 **cross-cutting 인프라**. 여러 도메인이 공유하는 휘발성 키-값 스토어(TTL·캐시·블랙리스트). config 작업 시 자동 로드되는 [`global/config/CLAUDE.md`](../../src/main/java/com/diving/pungdong/global/config/CLAUDE.md) 가 여기로 링크한다.

## 1. 한 줄

`RedisTemplate<String, String>` 하나(`global/config/RedisConfig`, Lettuce)로 **세 가지 휘발성 용도**를 쓴다 — 영속 데이터는 MySQL, Redis 는 TTL/캐시/블랙리스트 전용.

## 2. 어디서·왜 쓰나

| 용도 | 키 | 위치 | 왜 Redis |
|---|---|---|---|
| **JWT 블랙리스트** (로그아웃 토큰 무효화) | 토큰 jti → `"false"` | `global/security/JwtAuthenticationFilter`·`SignController`(`/sign/logout`) | 토큰 만료까지 TTL, stateless 인증의 유일한 무효화 수단 |
| **이메일 인증 코드** (가입/복구) | email → code (TTL) | `account/EmailService` | 짧은 TTL 자동 만료 |
| **OFFICIAL 위치 캐시** (Sanity cache-aside) | `venue:official:*` | `venue/sync/OfficialVenueCache` | Sanity 서버사이드 읽기 결과를 캐싱(`_rev` reconcile + 웹훅으로 신선도 유지). 상세 [venue.md](venue.md) §6 |

## 3. config

- **main**: `global/config/RedisConfig` — `spring.redis.host`/`port` 로 `LettuceConnectionFactory` + `RedisTemplate<String,String>`(키·값 둘 다 `StringRedisSerializer`). `@EnableRedisRepositories`.
- **test**: `global/config/EmbeddedRedisConfig`(`src/test/`) — 임베디드 Redis 를 **고정 포트 16379** 로 띄움. `@SpringBootTest` 가 AutoConfiguration.imports 로 자동 로드, static 1회.

## 4. ⚠️ 테스트는 dev/운영 Redis 를 오염시키면 안 된다 (사고 #62, 2026-06-14)

**겪은 사고**: 테스트(`@ActiveProfiles("test")`, `StubSanityVenueClient`)가 stub OFFICIAL 위치 캐시를 **docker Redis(6379)에 써서**, 로컬 dev BE 가 그걸 읽어 코스 빌더의 OFFICIAL 위치가 stub 으로 보였다(name "일반권" vs Sanity "일반권 (3시간)", 하프/종일 누락). `./gradlew test` 한 번이면 재오염 → 매번 수동 flush 필요.

**원인**: `application-test.yml` 의 `spring.redis.port` 가 docker Redis 와 같은 **6379** 였고, `EmbeddedRedisConfig` 가 임의 free port 를 `System.setProperty("spring.redis.port", …)` 로 박았지만 그 static 블록이 `RedisAutoConfiguration` 의 `RedisProperties` 바인딩보다 **늦게** 적용돼 yml 의 6379(=docker)로 fallback.

**해결**: EmbeddedRedis 를 **고정 16379** + `application-test.yml` 도 **16379** 로 일치(`EmbeddedRedisConfig.TEST_REDIS_PORT`). 바인딩 타이밍과 무관하게 항상 임베디드를 써 docker Redis(6379) 비오염. 검증: docker Redis flush → `./gradlew test` → docker Redis 의 `venue:official:*` 가 여전히 비어있음.

**원칙 (재발 방지 — Redis/캐시/DB 작업 시 반드시)**:
1. 테스트 인프라(임베디드 Redis/H2/Testcontainers)는 dev docker 인프라(**redis 6379 · mysql 3306 · es 9200**)와 **포트/이름을 격리**한다. 테스트가 새 외부 의존을 쓰면 dev 와 다른 포트·dataset 인지 **먼저 확인**.
2. `System.setProperty` 류 런타임 주입은 auto-config 바인딩 타이밍에 의존하니 믿지 말고, **고정 격리값을 yml + 코드 양쪽에 일치**시킨다.
3. 증상이 "데이터가 stub/이상함"이면 **앱 코드보다 dev 캐시·DB 오염을 먼저 의심**(인증 CLI 경로엔 정상인데 public 경로만 이상하면 환경 오염 신호).

## 5. 운영

- local: docker compose `redis` (6379). dev BE 는 `application.yml`/`redis.yml` 로 연결.
- prod: ElastiCache(Phase 4, [[phase_4_deployment_decisions]]) — `redis.yml`(gitignore, `.example` 커밋) / filesystem config override.
- `OfficialVenueCache` reconcile 잡 liveness 는 `/actuator/health` heartbeat (초기지연 30초, 이후 10분 주기).
