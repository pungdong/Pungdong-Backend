# CLAUDE.md — ota (OTA 텔레메트리 / 릴리스 대시보드 / 앱 정책)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

## 작업 전 반드시 읽기

- **[docs/features/ota-telemetry.md](../../../../../../../docs/features/ota-telemetry.md)** — 정책·왜·결정 히스토리
- **[docs/architecture/ota.md](../../../../../../../docs/architecture/ota.md)** — 컴포넌트·흐름·ER·권한 매트릭스
- 컨트롤러 시그니처/응답/enum 을 바꾸면 **같은 PR 에서** [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신

## 무엇이 들어있나

- **수집(permitAll)**: `OtaTelemetryController`(`POST /app/ota/devices`, `.../{installId}/events`) ·
  `OtaTelemetryService` · `OtaClientIpResolver`
- **어드민**: `AdminOtaController`(`/admin/ota/**` 4종) · `OtaAdminService`
- **앱 정책**: `AppPolicyController`(`GET /app/policy`) · `AdminAppPolicyController`(`PUT /admin/app/policy`) ·
  `AppPolicyService`
- **엔티티/레포**: `OtaDevice` · `AppPolicy` · `OtaDeviceJpaRepo` · `AppPolicyJpaRepo`
- **유틸**: `OtaCrashHistory`(JSON 배열 취급 + SQL/Java 술어 일치) · `OtaEventType` · `OtaDeviceState`
- **탈퇴 연동**: `OtaDeviceAnonymizationListener` — `account` 의 `AccountAnonymizedEvent` 수신,
  **링크만 끊고 행은 남긴다**(행 자체는 PII 가 아니고, 지우면 대시보드 분모에 구멍이 난다)

## 이걸 모르고 고치면 깨지는 것

1. 🔒 **`installId` 는 인증 수단이 아니다**(암호학적 난수가 아님). **이 값을 키로 하는 비인증 *읽기* 경로를
   추가하지 말 것.** 지금은 비인증이 쓰기 전용이고 읽기는 ADMIN 뒤에 하나뿐이라 안전하다.
2. **관측은 관용적으로.** 앱은 4xx 를 조용히 삼키고 재시도하지 않는다 — 검증을 조이면 그 기기가 **영구히 집계
   밖**이 되고 아무 신호도 안 남는다. `appVersion`(2자리 허용)·`fingerprintHash`(형식 미검증)·크래시 이력
   (원소만 폐기)의 느슨함은 전부 의도된 것이다. **`PUT /admin/app/policy` 의 엄격한 semver 와의 비대칭도 의도.**
3. **카운트와 목록은 같은 술어를 써야 한다.** `OtaBundleStats` 필드 ↔ `OtaDeviceState` 값이 1:1 이고,
   특히 `crashRolledBack` 은 카운트가 Java(`OtaCrashHistory.contains`), 목록이 SQL(`likePattern`)로 평가된다 —
   **둘 다 `OtaCrashHistory` 를 통과시켜야 갈리지 않는다.** `OtaAdminUseCaseTest` A7 이 잠근다.
4. **`installed` 는 누적이 아니다.** `ota_bundle_id` 는 부팅마다 덮어쓰는 현재 상태라 다른 번들로 넘어간 기기는
   빠진다. 정의문을 바꿀 땐 §2.0 표와 어드민 툴팁이 같이 움직인다.
5. **`crashRollbackReportedAt` 은 크래시 시각이 아니라 보고 시각**이다. 이름을 줄이지 말 것 —
   그 이름이 "배포 3일 뒤 크래시" 오독을 막는 유일한 장치다.
6. **`serverRolledBack` 은 BE 파생이다** — 앱 이벤트는 보조다. 앱 콜백은 롤백 *대상*(어디로)을 주는데
   컬럼은 *출발*(어디에서)이고, 게다가 강제 리로드 뒤에 있어 부팅 경로에선 실행되지 않는다.
   파생 로직(`deriveServerRollback`)을 지우면 **에픽 완료 기준이 보는 숫자가 0 근처로 죽는다.**
   크래시 롤백과의 이중 계산 방지는 **양방향 둘 다** 필요하다(도착 순서가 보장되지 않는다).
7. **BE 는 Cloudflare D1 을 읽지 않는다.** 번들 메타를 여기에 넣고 싶어지면 먼저
   `docs/features/ota-telemetry.md` §역할 분담을 읽을 것(라이브러리 스키마가 8개월에 두 번 바뀌었다).
8. **IP 상한은 fail-open.** `OtaClientIpResolver` 는 `X-Forwarded-For` 의 **마지막 홉**을 본다(ALB 가 덧붙인
   실 클라이언트 IP). `server.forward-headers-strategy` 를 켜지 않은 이유는 그게 전역 설정이라 이 피처 밖까지
   동작이 바뀌기 때문 — 영향 범위를 이 클래스 하나로 가뒀다.

## 안전망 테스트

`src/test/.../usecase/OtaTelemetryUseCaseTest`(S/E/V) · `OtaAdminUseCaseTest`(R/A) ·
`OtaTelemetryRateLimitUseCaseTest`(L) · `AppPolicyUseCaseTest`(P).

> ⚠️ 테스트는 H2 + Flyway OFF 라 **마이그레이션을 실행하지 않는다.** 엔티티 컬럼을 건드리면 `V29` 이후의 새
> 마이그레이션을 같은 PR 에 넣고, **빈 docker MySQL 에 부팅해 Flyway 실행까지** 확인할 것.
