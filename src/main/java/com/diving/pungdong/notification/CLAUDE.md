# CLAUDE.md — notification (알림 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **domain-based(package-by-feature)** 로 정착된 두 도메인 중 하나 ([account](../account/CLAUDE.md) 와 함께). 한 폴더에 service·entity·event·repo·fcm 게이트웨이를 모았다.

## 무엇이 들어있나

도메인 이벤트 → Outbox → FCM 전송 파이프라인 전부:
- **이벤트**: `event/ReservationCreatedEvent`, `ReservationCancelledEvent`, `LectureNotificationEvent` — 도메인에서 `ApplicationEventPublisher` 로 발행
- **Outbox**: `NotificationOutboxWriter`(리스너, PENDING 행 기록), `NotificationOutbox` 엔티티, `NotificationStatus`(PENDING/FAILED/SENT/GAVE_UP), `NotificationType`
- **워커**: `NotificationDeliveryWorker`(@Scheduled, PENDING/FAILED 픽업 → 전송 → 상태전이, exp backoff 10회 → GAVE_UP), `NotificationDispatcher`, `NotificationPayload`
- **FCM**: `fcm/FcmGateway`(인터페이스), `FirebaseFcmGateway`(실전송 + UNREGISTERED/INVALID/NOT_FOUND 시 토큰 행 삭제), `LoggingFcmGateway`(로컬/스텁). **둘은 `firebase.enabled` 프로퍼티로 상호배타 키잉**(true=Firebase, false/미설정=Logging) — `@ConditionalOnMissingBean`/`@ConditionalOnBean` 으로 바꾸지 말 것(↓ 결정 히스토리).
- **retention**: `NotificationOutboxRetention`(@Scheduled 매일 4am, SENT 30일↑ 삭제. FAILED/GAVE_UP 영구보존)
- **레포**: `NotificationOutboxJpaRepo`

`FirebaseToken` 엔티티는 여기 아님 — **[account](../account/CLAUDE.md)** 소유 (토큰은 사용자가 가진 데이터, 알림은 소비자). 이 도메인은 account 의 토큰을 읽기만.

## 작업 전 반드시 읽기

- **[docs/architecture/notification.md](../../../../../../../docs/architecture/notification.md)** — 이벤트→outbox→FCM 흐름, 상태 머신, retention
- memory `project_simplification_plan` (Phase 2 설계: outbox 상태/흐름, FirebaseToken 설계)

## 결정 히스토리 (왜 이렇게 됐나)

- **Kafka 제거 → 도메인 이벤트 + Outbox 패턴** (Phase 2, PR #9~#14). 외부 서비스 동기화용이던 `account`/`update-account` 토픽은 삭제, `firebase-token` 은 DB 직접 저장, 예약/강의 알림은 도메인 이벤트로.
- **상태 머신**: PENDING→(worker)→SENT / 실패 시 FAILED→재시도(exp backoff 10회)→GAVE_UP(human attention, log.warn). 어드민 엔드포인트는 출시 후 운영 필요 시.
- **reactive 토큰 정리만**: FCM 에러 응답(UNREGISTERED 등)으로 죽은 토큰 삭제. 시간 기반 정리 없음 — 저빈도 사용자(수강생은 1년에 한 번급) 도메인이라 last_seen 기반 정리는 틀린 축.
- **retention**: SENT 만 30일 후 삭제, FAILED/GAVE_UP 영구보존(포렌식).
- **FCM 게이트웨이 선택 = 프로퍼티 키잉 (`@ConditionalOnMissingBean` 금지)** — `LoggingFcmGateway`/`FirebaseFcmGateway` 는 `@ConditionalOnProperty("firebase.enabled")` 의 반대값으로 잠근다. 예전 `@ConditionalOnMissingBean(name="firebaseFcmGateway")` 은 **컴포넌트 스캔에서 평가 순서가 비보장**이라, 무관한 클래스(#93·#94)가 추가돼 스캔 순서가 바뀌자 prod(`FIREBASE_ENABLED=false`)에서 FcmGateway 빈이 하나도 안 떠 **부팅이 크래시 루프**(APPLICATION FAILED TO START, #97). 6/24 이미지는 우연히 순서가 맞아 동작했음. **컴포넌트 스캔 빈끼리 `@ConditionalOnMissingBean`/`@ConditionalOnBean` 금지** — 프로퍼티로 결정론적 키잉(회귀 테스트 `FcmGatewayWiringTest`). 메모리 `feedback_conditional_bean_wiring`.

## 안전망 테스트

`src/test/.../usecase/NotificationOutboxFlowTest` — 이벤트 발행 → outbox 행 → 워커 처리 lifecycle 검증. `FcmGateway` 는 진짜 외부 경계라 mock OK.
