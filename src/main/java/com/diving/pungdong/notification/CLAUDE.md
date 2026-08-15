# CLAUDE.md — notification (알림 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **domain-based(package-by-feature)** 로 정착된 두 도메인 중 하나 ([account](../account/CLAUDE.md) 와 함께). 한 폴더에 service·entity·event·repo·fcm 게이트웨이를 모았다.

## 무엇이 들어있나

도메인 이벤트 → Outbox → FCM 전송 파이프라인 전부:
- **이벤트**: `event/` 아래. ⚠️ `ReservationCreatedEvent`·`ReservationCancelledEvent`·`LectureNotificationEvent` **3종은 발행처가 없다** — 유일한 발행처였던 v1 `service/reservation/ReservationService`·`service/LectureService` 가 레거시 청산(2026-08-15)으로 삭제됐다. 클래스와 `NotificationOutboxWriter` 의 리스너, `NotificationType` enum 값은 남겼다(과거 `user_notification` 행이 그 type 문자열을 갖고 있어 enum 을 지우면 옛 알림 조회가 깨진다). 두 use-case 테스트가 이 경로를 계속 태우지만 **프로덕션에서는 발생하지 않는 경로**임에 유의. 나머지 이벤트(수강·결제·커뮤니티)는 각 도메인에서 `ApplicationEventPublisher` 로 발행된다.
- **Outbox**: `NotificationOutboxWriter`(리스너, PENDING 행 기록), `NotificationOutbox` 엔티티, `NotificationStatus`(PENDING/FAILED/SENT/GAVE_UP), `NotificationType`
- **워커**: **`NotificationDispatcher`** 가 `@Scheduled`(기본 3초, `@Profile("!test")`) 로 PENDING/FAILED 를 batch 50 픽업하고, **`NotificationDeliveryWorker`** 가 건별 `@Transactional(REQUIRES_NEW)` 로 전송·상태전이(exp backoff 10회 → GAVE_UP). ⚠️ **`@Scheduled` 는 워커가 아니라 디스패처에 있다.** `NotificationPayload` 는 payload DTO.
- **FCM**: `fcm/FcmGateway`(인터페이스), `FirebaseFcmGateway`(실전송 + UNREGISTERED/INVALID/NOT_FOUND 시 토큰 행 삭제), `LoggingFcmGateway`(로컬/스텁). **둘은 `firebase.enabled` 프로퍼티로 상호배타 키잉**(true=Firebase, false/미설정=Logging) — `@ConditionalOnMissingBean`/`@ConditionalOnBean` 으로 바꾸지 말 것(↓ 결정 히스토리).
- **retention**: `NotificationOutboxRetention`(@Scheduled 매일 4am, SENT 30일↑ 삭제. FAILED/GAVE_UP 영구보존)
- **알림함(인앱 수신함)**: `UserNotification`(엔티티) · `UserNotificationJpaRepo` · `UserNotificationService` · `UserNotificationController`(`/me/notifications` 목록/미읽음수/읽음/전체읽음) · `dto/UserNotificationResponse`(`@Relation("notifications")`) · `dto/UnreadCountResponse` · `NotificationPaging`(size 상한 50, 클라 정렬 무시)
- **레포**: `NotificationOutboxJpaRepo`, `UserNotificationJpaRepo`

`FirebaseToken` 엔티티는 여기 아님 — **[account](../account/CLAUDE.md)** 소유 (토큰은 사용자가 가진 데이터, 알림은 소비자). 이 도메인은 account 의 토큰을 읽기만.

## 작업 전 반드시 읽기

- **[docs/architecture/notification.md](../../../../../../../docs/architecture/notification.md)** — **여기부터.** 이벤트→outbox+알림함 이중 INSERT→디스패처→FCM 흐름, 상태 머신, retention, 그리고 **§상황별 알림 카탈로그**(어떤 상황에 어떤 알림이 어떤 문구·`data` 로 나가는가 = 단일 출처. 미구현 5종도 "왜 없는지"와 함께 명시돼 있다).
- **[docs/features/push.md](../../../../../../../docs/features/push.md)** — 정책·계약(FE SoT)·결정 히스토리. **🔴 prod 는 아직 `FIREBASE_ENABLED=false`(stub)** 라 실발송은 staging 에서만 된다 — 그 현황도 여기.
- memory `project_simplification_plan` (Phase 2 설계: outbox 상태/흐름, FirebaseToken 설계)

## 결정 히스토리 (왜 이렇게 됐나)

- **Kafka 제거 → 도메인 이벤트 + Outbox 패턴** (Phase 2, PR #9~#14). 외부 서비스 동기화용이던 `account`/`update-account` 토픽은 삭제, `firebase-token` 은 DB 직접 저장, 예약/강의 알림은 도메인 이벤트로.
- **상태 머신**: PENDING→(worker)→SENT / 실패 시 FAILED→재시도(exp backoff 10회)→GAVE_UP(human attention, log.warn). 어드민 엔드포인트는 출시 후 운영 필요 시.
- **reactive 토큰 정리만**: FCM 에러 응답(UNREGISTERED 등)으로 죽은 토큰 삭제. 시간 기반 정리 없음 — 저빈도 사용자(수강생은 1년에 한 번급) 도메인이라 last_seen 기반 정리는 틀린 축.
- **retention**: SENT 만 30일 후 삭제, FAILED/GAVE_UP 영구보존(포렌식).
- **FCM 게이트웨이 선택 = 프로퍼티 키잉 (`@ConditionalOnMissingBean` 금지)** — `LoggingFcmGateway`/`FirebaseFcmGateway` 는 `@ConditionalOnProperty("firebase.enabled")` 의 반대값으로 잠근다. 예전 `@ConditionalOnMissingBean(name="firebaseFcmGateway")` 은 **컴포넌트 스캔에서 평가 순서가 비보장**이라, 무관한 클래스(#93·#94)가 추가돼 스캔 순서가 바뀌자 prod(`FIREBASE_ENABLED=false`)에서 FcmGateway 빈이 하나도 안 떠 **부팅이 크래시 루프**(APPLICATION FAILED TO START, #97). 6/24 이미지는 우연히 순서가 맞아 동작했음. **컴포넌트 스캔 빈끼리 `@ConditionalOnMissingBean`/`@ConditionalOnBean` 금지** — 프로퍼티로 결정론적 키잉(회귀 테스트 `FcmGatewayWiringTest`). 메모리 `feedback_conditional_bean_wiring`.

- **알림함 ≠ outbox (겸용 금지)** — outbox 는 "단말에 밀어넣기 성공했나"(전송 시도 원장, SENT 30일 후 삭제, 토큰 없으면 `GAVE_UP`)이고 `user_notification` 은 "이 유저에게 무슨 일이 있었나"(사실 원장, 영구 보존). **웹 사용자·앱 미설치 사용자는 전부 `GAVE_UP` 이 되는데 그들이야말로 알림함이 가장 필요한 대상**이라, 겸용하면 durability 목적 자체가 무너진다. 둘은 `notificationId`(UUID)로 1:1 상관되고 `enqueue` 가 **같은 트랜잭션**에서 함께 쓴다.
  - ⚠️ **`notification_id` 는 `VARCHAR(36)` 이다 — `CHAR(36)` 으로 쓰면 부팅이 깨진다.** 엔티티가 `String` + `length=36` 이라 Hibernate 는 `varchar(36)` 을 기대하는데, `CHAR` 이면 `hbm2ddl=validate` 가 `wrong column type ... found [char], but expecting [varchar(36)]` 로 **거부**한다. **테스트는 H2 + Flyway OFF 라 엔티티에서 스키마를 만들어 이 불일치를 절대 못 잡는다** — 실제로 V23 을 그렇게 썼다가 빈 MySQL 부팅 검증에서 잡았다(안 했으면 첫 발견이 prod 크래시 루프). 마이그레이션을 새로 쓸 때 **엔티티 타입과 SQL 타입을 나란히 놓고 대조**할 것.
  - ⚠️ **`enqueue` 는 알림함 title/body 를 컬럼 길이(255/500)로 자른다.** outbox payload 는 `@Lob` 이라 길이 제한이 없어서, 안 자르면 긴 `LECTURE_NOTIFICATION` 본문이 `Data too long` 을 내고 **같은 트랜잭션인 비즈니스 작업까지 롤백**시킨다(수강신청이 알림 때문에 실패).
- **디스패처 poison-pill 방어** — `dispatch()` 루프는 **행 단위 try/catch** 로 감싸고 예외 시 `recordDeliveryFailure`(REQUIRES_NEW)로 실패를 기록한다. 없으면 깨진 payload 행 하나가 `ORDER BY createdAt ASC` 선두에 계속 재선택되며 **큐 전체를 영구 정지**시킨다(`attempts` 가 안 올라 `GAVE_UP` 구제도 안 됨). stub 게이트웨이는 예외를 안 던져 **실전송 전환 순간 발현**하는 종류다. 이 try/catch 를 지우지 말 것 — 회귀 테스트 `P1`·`P2`.

## 안전망 테스트

- `src/test/.../usecase/NotificationOutboxFlowTest` — 이벤트 발행 → outbox 행 → 워커 처리 lifecycle + **poison-pill(`P1`·`P2`)**. `FcmGateway` 는 진짜 외부 경계라 mock OK.
- `src/test/.../usecase/NotificationCenterUseCaseTest` — 알림함 HTTP 사양(`S*` 성공 / `R*` 권한 / `V*` 검증 / `X*` 트랜잭션). **`X1` 이 `MANDATORY` 전파 회귀 테스트**(비즈니스 롤백 시 알림함 행도 사라짐), `X2` 는 "푸시 실패해도 알림함엔 남는다" 를 고정한다.
