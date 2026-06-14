# CLAUDE.md — global/config (인프라 @Configuration)

도메인 무관 **인프라 빈**들이 모인 곳. 작업 디렉토리가 여기면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

## 무엇이 들어있나

- **`RedisConfig`** — `RedisTemplate<String,String>`(Lettuce, `spring.redis.host`/`port`). Redis 의 **용도(JWT 블랙리스트·이메일 코드·OFFICIAL 위치 캐시)와 운영/테스트 이슈는 [docs/architecture/redis.md](../../../../../../../docs/architecture/redis.md)**.
- **`ElasticSearchConfig`** — `@Profile("!test")`. Phase 3 제거 대상(테스트는 mock).
- **`EmailConfig`** — 메일 발송(SMTP/SES).
- **`FirebaseConfig`** — FCM (ADC/WIF 자격증명).
- **`MessageConfiguration`** — i18n `MessageSource`(`i18n/exception*.yml`).
- (test, `src/test/.../global/config/`) **`EmbeddedRedisConfig`**(임베디드 Redis **16379**), `RestDocsConfiguration`, `TestElasticSearchConfig`.

## ⚠️ Redis 작업 시 — 테스트가 dev/운영 Redis 를 오염시키면 안 된다

**`EmbeddedRedisConfig` 는 고정 포트 16379** 로 띄우고 `application-test.yml` 의 `spring.redis.port` 도 16379 다 — **docker Redis(6379)와 분리**돼 있다. 이 둘을 **다시 6379 로 합치거나, 임의 포트 + `System.setProperty` 같은 타이밍 의존 방식으로 되돌리지 말 것.**

> 이전에 테스트가 docker Redis(6379)를 공유해, 테스트의 stub OFFICIAL 위치 캐시가 로컬 dev 로 누설돼 코스 빌더가 stub 을 보던 사고(#62, 2026-06-14)가 있었다. 긴 디버깅을 유발했다(인증 CLI 엔 정상, public 만 이상). **원칙·재발 방지 상세: [docs/architecture/redis.md](../../../../../../../docs/architecture/redis.md) §4.**

일반 원칙: 테스트 인프라(임베디드 Redis/H2/Testcontainers)는 dev docker 인프라(redis 6379·mysql 3306·es 9200)와 **포트/이름 격리**. 새 외부 의존을 테스트가 쓰면 dev 와 다른 포트인지 먼저 확인.
