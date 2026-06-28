# 테스트 아키텍처 — hermetic(외부 무의존) 원칙

> 횡단 관심사 문서(도메인 아님 — redis.md·security.md·observability.md 와 같은 결). **무엇을·왜**를 소유하고, *어떻게 쓰나*(use-case 작성 규약·실행 명령)는 루트 [CLAUDE.md](../../CLAUDE.md) 의 "Test setup" / "Use-case test convention" 로 링크.

## 한 줄 원칙

**단위·통합 테스트(`@SpringBootTest`)는 어떤 외부 서비스도 실제로 호출하지 않는다.** 실 스택(H2·Spring Security 필터·실 서비스/JPA)은 그대로 쓰되, **외부 경계(third-party HTTP·PG·SMS·푸시·오브젝트 스토리지)만 격리**한다. 이건 e2e 가 아니다 — 테스트 결과가 네트워크·외부 API 상태·개발자 셸 환경에 따라 달라지면 결정성과 CI 신뢰성이 깨진다.

## 왜 (the failure mode) — env 누출

`@SpringBootTest` gradle 테스트는 **셸 환경변수를 그대로 상속**한다. `.env.local`(direnv)이 export 한 값이 `application.yml` 의 `${ENV:default}` placeholder 로 새어들어와, `@ConditionalOnProperty` 로 stub↔real 을 가르는 외부 클라이언트가 **테스트에서 실 구현으로 활성화**될 수 있다. CI(키 없음)는 통과하지만 **로컬 full-suite 만 깨지는** 비결정성 → 추적에 시간 소모.

실제 사고 2건:
- **#82 (2026-06-24)** `ADDRESS_GEOCODE_MODE=juso` 누출 → `AddressUseCaseTest` 가 `JusoAddressApiClient`(실 juso API)를 써서 깨짐.
- **#120 (2026-06-28)** `PAYMENT_MODE=toss` 누출 → `RefundUseCaseTest` 가 `RealTossPaymentClient`(실 Toss API)를 써서 환불 시 400.

> "로컬에서만 테스트가 깨진다" = **셸 env 누출부터 의심**. `env | grep` 으로 게이트 env 를 찾고, 그게 어떤 클라이언트를 real 로 뒤집는지 확인. 같은 정신의 인프라 격리(테스트↔dev 분리)는 [redis.md](redis.md).

## 두 가지 격리 패턴

| 패턴 | 언제 | 어떻게 |
|---|---|---|
| **A. 프로파일 stub 핀** | 그 경계에 **in-process Stub\*Client 가 "테스트 기본 더블"로 이미 설계**돼 있을 때(`@ConditionalOnProperty matchIfMissing=true` 같은 의도된 기본) | `src/test/resources/application-test.yml` 에 dotted key 를 **리터럴**로 박는다(예: `pungdong.payment.mode: stub`). 테스트는 실제 Stub 구현을 거쳐 **서비스↔클라이언트 와이어링까지 커버**. |
| **B. `@MockBean`** | in-process 더블이 없거나, 테스트별로 **응답(성공/실패/엣지)을 직접 제어**해야 할 때 | use-case 테스트에서 그 클라이언트 빈을 `@MockBean` 으로 교체(고정 Stub\*Client 픽스처에 위임하면 단일 출처 유지). |

**A 가 env 누출을 이기는 원리**: OS env 는 `PAYMENT_MODE`(달러 placeholder 입력)만 제공하지 **dotted key `pungdong.payment.mode` 자체를 주지 않는다**. application.yml 의 `${PAYMENT_MODE:stub}` 해석값은 application.yml property source(저우선)에 들어가고, application-test.yml 의 **리터럴**은 같은 key 의 profile-specific source(고우선)라 이긴다. (핀에 또 `${ENV}` 를 쓰면 안 됨 — 리터럴이어야 함.) **B 는 우선순위 추론이 아예 불필요**(빈 자체를 대체)라 더 bulletproof — 그래서 토글이 애매하면 B.

> A·B 는 상보적: A 를 전역 baseline 으로 깔고, *실패 경로*를 단언하는 특정 테스트만 그 위에 B(`@MockBean`)로 실패를 주입.

## 외부 경계 인벤토리 (현재)

| 경계 | 클라이언트 | 테스트 격리 |
|---|---|---|
| 결제 PG | `TossPaymentClient`(Real/Stub, `pungdong.payment.mode`) | **A** — `application-test.yml` `pungdong.payment.mode: stub` (#120) |
| 주소·좌표 | `AddressApiClient`(Juso/Stub, `ADDRESS_GEOCODE_MODE`) | **B** — `@MockBean` (#82) |
| 본인확인(간편/휴대폰) | `IdentityVerifier` | **B** — `@MockBean` |
| Sanity(약관·venue) | `SanityTermClient`·`SanityVenueClient` | **B** — `@MockBean` |
| 사이트 설정 | `SiteSettingsProvider` | `TestSiteSettingsConfig`(`@Profile("test")` 고정값) + 필요 시 `@MockBean` |
| 푸시(FCM) | `FcmGateway`(`firebase.enabled`) | **B** — `@MockBean` / 미설정 시 no-op |
| 오브젝트 스토리지(S3) | `CertificateImageStorage`·`CourseImageStorage`(Local/S3) | Local 구현 또는 `@MockBean` |
| 캐시(Redis) | — | 임베디드 Redis **16379**(dev docker 6379 와 분리, [redis.md](redis.md)) |
| DB | — | H2 in-memory `create-drop`, Flyway off(MySQL SQL dialect 불일치) |

## 새 외부 서비스 붙일 때 (체크리스트)

간편인증·휴대폰 본인인증·정산 PG·SMS 등 **외부 의존이 추가될 때마다**:

1. 외부 호출은 **인터페이스 경계**로 감싼다(`XClient`). 가능하면 **in-process `StubXClient`** 를 두고 `@ConditionalOnProperty`(stub 기본) 로 Real 과 교체.
2. **테스트가 그 경계를 절대 실호출하지 않게** 보장 — Stub 이 설계 기본이면 **패턴 A**(application-test.yml 리터럴 핀), 아니면 **패턴 B**(`@MockBean`).
3. 게이트 env 가 direnv 에 있으면, 그 env 가 test JVM 으로 새도 **A 의 리터럴 핀 또는 B 의 빈 대체로 무력화**되는지 확인(켠 채로 full-suite green 검증).
4. 이 문서의 인벤토리 표 + 루트 CLAUDE.md "Test setup" 갱신.

## 관련

- 루트 [CLAUDE.md](../../CLAUDE.md) — "Test setup" / "Use-case test convention"(실행 명령·`@DisplayName` 규약·안전망 테스트)
- [redis.md](redis.md) — 테스트↔dev 인프라 분리(16379) 원리
- 메모리 `feedback_env_leak_into_tests` · `feedback_conditional_bean_wiring`(상호배타 빈은 프로퍼티 반대값 키잉) · `feedback_test_must_not_pollute_dev_infra`
