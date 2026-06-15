# CLAUDE.md — availability (강사 가용시간 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `Account`(instructor) · `InstructorApplication`(게이트) · `venue`(VenueRefValidator/VenueRefResolver) 를 **단방향 참조**.

## 무엇이 들어있나 — 강사 가용시간 캘린더(v1 코어)

강사가 매일 여는 메인 도구. V2 디자인 `features/instructor-availability` 의 BE. **2층 모델**:

- **가용시간 window** = *이론적 가능성* (강사가 연 빈 시간). 이 도메인이 소유 — `AvailabilityWindow`.
- **점유(occupancy)** = *실제 점유*. v1 은 **외부/수동 hold** 만 (`AvailabilityHold`, 단일 테이블, `memo` nullable):
  - `memo == null` ⇒ ± 빠른조정(메모 없는 +1).
  - `memo != null` ⇒ 외부예약(메모 + 인원 1~N, 정원 초과 시 자동 확장).

**5상태(`SlotStatus`)는 저장값이 아니라 점유에서 파생**(`AvailabilityService.deriveStatus`): `AVAILABLE · PENDING · CONFIRMED · EXTERNAL · FULL`. **풍덩 수강생 점유(`pending`/`confirmed`, `applicants[]`)는 enrollment/booking 도메인 산물 — v1 미연동이라 항상 0/빈 배열.** 응답 모양만 forward-compatible 하게 잡아 enrollment 가 붙으면 채운다. 즉 v1 캘린더는 `AVAILABLE` ↔ `EXTERNAL`/`FULL` 만 실제로 그려진다.

- **컨트롤러**: `AvailabilityController`(`/instructor/availability/**`). recurrence 생성(201, 다중 window) · 캘린더 읽기(`?from=&to=`) · 디테일 · 수정 · 삭제 · hold 추가/제거.
- **서비스**: `AvailabilityService`(recurrence 전개 · 5상태 파생 · 정원 자동확장 · 게이트). 응답은 **트랜잭션 안에서 DTO 매핑**(LAZY hold 보호). venueName 은 `VenueRefResolver` 배치 해석(N+1 회피).
- **엔티티**: `AvailabilityWindow`(instructor·date·시간·capacity·nullable venueRefId/sessionLabel) → `AvailabilityHold`. enum: `RecurrenceMode`(ONCE/WEEKLY/FOUR_WEEKS)/`SlotStatus`.
- **레포**: `AvailabilityWindowJpaRepo.findByInstructorIdAndDateBetween...`, `AvailabilityHoldJpaRepo`(주로 테스트 점유 확인).
- **dto/**: `AvailabilityCreateRequest`(recurrence) · `AvailabilityUpdateRequest` · `HoldRequest` · `AvailabilityWindowResponse`(중첩 `HoldResponse`) · `ApplicantSummaryResponse`(v1 빈 배열, 모양만).

보안 매처(`/instructor/availability/**` → authenticated)는 **`global/security/SecurityConfiguration`**. 역할이 아니라 인증인 이유는 venue 와 동일: 리뷰 대기(SUBMITTED) 강사신청자는 아직 STUDENT 라서. 실제 게이트(강사신청 보유, 종목 무관)는 서비스 `requireInstructorTrack` 가 `InstructorApplicationJpaRepo.existsByAccountId` 로 강제.

## 작업 전 반드시 읽기

- **[docs/features/instructor-availability.md](../../../../../../../docs/features/instructor-availability.md)** — **정책·왜·히스토리**(2층 모델, 단일 hold 테이블, 정원 자동확장, enrollment 의존, venue∩availability 후속). **여기부터.**
- **[docs/architecture/availability.md](../../../../../../../docs/architecture/availability.md)** — 구현(흐름/ER/권한 매트릭스)
- **[venue/CLAUDE.md](../venue/CLAUDE.md)** — `venueRefId` 토큰·`VenueRefValidator`/`VenueRefResolver` 규칙
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **2층 모델** (V2 디자인 브리프) — 가용시간 window = 이론적 가능성 / 점유 = 실제. 출처(풍덩 수강생/외부)와 무관하게 동일 동작하도록 단일 정의.
- **두 가지 정원 조정 = 단일 테이블** — ± 빠른조정과 외부예약을 같은 `AvailabilityHold` row 로(`memo` 로만 구분). 모델을 단단하게.
- **정원 초과 = 자동 확장** — 외부 단체가 정원을 넘기면 강사가 막히지 않게 capacity = 점유로 확장. 축소(수정)는 점유 미만으로 못 내림(400).
- **게이트 = 강사신청 보유(상태 무관)** — venue 와 동일 기조. 리뷰 대기 중에도 가용시간 준비 허용. → [[instructor-review-window-allows-prep]]. (가용시간은 종목별이 아니라 강사 단위라 종목 조건 없음 — `existsByAccountId`.)
- **없음/비소유 = 400 통일**(`ResourceNotFoundException`) — 남의 일정 존재 숨김(venue 패턴).
- **applicants 빈 채로 v1** — enrollment 도메인 부재. 5상태 모델은 완비, `PENDING`/`CONFIRMED` 만 enrollment 가 붙을 때 채움.

## 안전망 테스트

`src/test/.../usecase/AvailabilityUseCaseTest` — 실 H2 + 시큐리티 체인(EmbeddedRedis 불필요). S(성공:생성/조회/수정/삭제)/H(점유 hold·정원확장·상태파생)/G(게이트·인증)/R(권한·격리)/V(검증). ⚠️ `Authorization` 헤더는 **raw JWT**(Bearer prefix 없음). ⚠️ hold 는 LAZY — 트랜잭션 밖 DB 확인은 `AvailabilityHoldJpaRepo.findByWindowId`.

## enrollment 연동됨 (PR #66)

- **풍덩 enrollment 연동 완료** — `pending`/`confirmed` 점유 + `applicants[]` 가 [enrollment](../enrollment/CLAUDE.md) 도메인에서 실데이터로 채워진다(`AvailabilityService.toResponse` 가 window별 enrollment 집계). 즉 **availability → enrollment(repo) 단방향 의존**(읽기 전용)이 생겼다. 첫 신청이 window 를 (venue,세션)으로 bind(`enrollment.WindowBinder`).
- **강사 availability ∩ Venue 운영시간 교차 = 수강생 선택지** — enrollment 의 `EnrollmentOptionsService`·`BookableSlotDeriver` 가 구현. → [[venue-domain-concept]].

## 아직 안 한 것 (후속 PR)
- **알림** — 대기 신청 배너의 푸시(notification outbox 연동) — enrollment 와 함께.
- **OFFICIAL venueRef 의 Sanity 서버사이드 읽기/캐시** — venue 동기화 설계 [[venue-sanity-sync-design]].
- **REST Docs `document(...)` 컨트롤러 테스트** — venue/course 와 동일하게 미작성(usecase 로 대체).
