# CLAUDE.md — availability (강사 가용시간 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `Account`(instructor) · `InstructorApplication`(게이트) · `venue`(VenueRefValidator/VenueRefResolver) · `enrollment`(repo, 읽기 전용) 를 **단방향 참조**.

## 무엇이 들어있나 — 강사 가용시간 캘린더 (coverage + session 2층)

강사가 매일 여는 메인 도구. V2 디자인 `features/instructor-availability` 의 BE. **두 레이어로 분리**(2026-06-18 — 옛 단일 `AvailabilityWindow` 가 시간·위치·정원·점유를 한 줄에 뭉쳤던 걸 쪼갬):

- **예약가능시간(coverage)** = `AvailabilityCoverage` — **순수 시간 띠**. "이 범위 안에서 예약을 받을 수 있다"는 판정값일 뿐 위치/정원/점유 아무것도 귀속하지 않는다. 한 (instructor, date) 의 coverage row 들은 항상 **비겹침·비인접으로 머지**돼 저장된다(10–12 + 12–14 → 10–14 한 줄). 머지/빼기/포함판정은 순수함수 `CoverageMerger`(union/subtract/normalize/containsWhole/overlapsAny). row id 는 머지/분할로 **휘발성**이라 다른 엔티티가 FK 로 참조하지 않는다 — coverage↔session 결합은 **시간 포함 판정뿐**.
- **일정(session)** = `AvailabilitySession` — **위치·정원·점유** 레이어. 정체성 = `(instructor, date, venueRefId, startTime, endTime)`. 같은 (위치,시간)에 점유를 또 추가하면 새 session 이 아니라 기존에 **누적**(외부 hold 또는 enrollment join). 점유 = 외부/수동 `AvailabilityHold`(단일 테이블, `memo` nullable) + 풍덩 enrollment(`enrollment.session_id`, enrollment 도메인 소유):
  - `memo == null` ⇒ ± 빠른조정(메모 없는 +1).
  - `memo != null` ⇒ 외부예약(메모 + 인원 1~N, 유효정원 초과해도 기록 — FULL 표시, 자동확장 없음).

**5상태(`SlotStatus`)는 저장값이 아니라 점유에서 파생**(`AvailabilityService.deriveStatus`): `AVAILABLE · PENDING · CONFIRMED · EXTERNAL · FULL`. 풍덩 수강생 점유(`pending`/`confirmed`, `applicants[]`)는 enrollment 도메인이 채운다(연동됨, 아래).

- **컨트롤러**: `AvailabilityController`(`/instructor/availability/**`).
  - coverage: `POST /coverage`(열기·union, recurrence ONCE/WEEKLY/FOUR_WEEKS) · `DELETE /coverage`(닫기·subtract, 단일 날). 닫기가 session 을 가로지르면 **거부**(`COVERAGE_HAS_SESSION` / `-1014`).
  - session: `POST /sessions`(**원자 추가** 201) · `GET/DELETE /sessions/{id}` · `PATCH/DELETE /sessions/{id}/capacity`(override 설정/해제) · `POST /sessions/{id}/holds` · `DELETE /sessions/{id}/holds/{holdId}`.
  - 범위 조회 `GET ?from&to` → `{coverage: CoverageRangeResponse[], sessions: AvailabilitySessionResponse[]}` (두 레이어 분리 — 옛 `_embedded.windows` HAL 아님). 정원 baseline `GET/PATCH /settings`.
- **서비스**: `AvailabilityService`(coverage 머지·교체 · session find-or-create · 원자 추가 · 5상태 파생 · 정원 baseline/override · 게이트). 응답은 **트랜잭션 안에서 DTO 매핑**(LAZY hold 보호). venueName 은 `VenueRefResolver` 배치 해석(N+1 회피), enrollment 집계는 `EnrollmentJpaRepo` 일괄 조회.
- **엔티티**:
  - `AvailabilityCoverage`(instructor·date·startTime·endTime — 순수 시간, FK 대상 아님).
  - `AvailabilitySession`(instructor·date·시간·**nullable `capacityOverride`**·nullable `venueRefId`/`sessionLabel`) → `AvailabilityHold`(`session_id` FK).
  - 정원 기본값은 `Account.defaultCapacity`(기본 4)에 있고 session 은 override 없으면 라이브 참조(`effectiveCapacity() = capacityOverride ?? instructor.effectiveDefaultCapacity()`). enum: `RecurrenceMode`(ONCE/WEEKLY/FOUR_WEEKS)/`SlotStatus`.
- **레포**: `AvailabilityCoverageJpaRepo`(`findByInstructorIdAndDate` 통째 로드해 교체 · `...DateBetween...` 범위 읽기), `AvailabilitySessionJpaRepo`(`...DateBetween...` 캘린더 · `findBy...DateAndStartTimeAndEndTime` find-or-create · `findByInstructorIdAndDate` coverage 닫기 충돌 판정), `AvailabilityHoldJpaRepo.findBySessionId`(주로 테스트 점유 확인).
- **dto/**: `CoverageRequest`(열기/닫기 공용, recurrence 필드) · `SessionCreateRequest`(원자 추가: date·시간·venueRef?·label?·count?·memo?·capacity?) · `HoldRequest` · `CapacityRequest` · `CoverageRangeResponse`(머지 구간 1개) · `AvailabilityCalendarResponse`(`coverage[]`+`sessions[]`) · `AvailabilitySessionResponse`(중첩 `HoldResponse`) · `AvailabilitySettingsResponse` · `ApplicantSummaryResponse`(평탄 3종, 아래 booking).

보안 매처(`/instructor/availability/**` → authenticated)는 **`global/security/SecurityConfiguration`**. 역할이 아니라 인증인 이유는 venue 와 동일: 리뷰 대기(SUBMITTED) 강사신청자는 아직 STUDENT 라서. 실제 게이트(강사신청 보유, 종목 무관)는 서비스 `requireInstructorTrack` 가 `InstructorApplicationJpaRepo.existsByAccountId` 로 강제.

## 작업 전 반드시 읽기

- **[docs/features/instructor-availability.md](../../../../../../../docs/features/instructor-availability.md)** — **정책·왜·히스토리**(coverage/session 분리, 머지, 원자 추가, 단일 hold 테이블, 정원 확정 바닥, enrollment 의존). **여기부터.**
- **[docs/architecture/availability.md](../../../../../../../docs/architecture/availability.md)** — 구현(흐름/ER/권한 매트릭스)
- **[venue/CLAUDE.md](../venue/CLAUDE.md)** — `venueRefId` 토큰·`VenueRefValidator`/`VenueRefResolver` 규칙
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **coverage / session 분리 (2026-06-18)** — 옛 단일 `AvailabilityWindow` 가 *시간·위치·정원·점유*를 한 줄에 뭉쳐 의미가 충돌했다(같은 시간대 다른 위치 = window 2개? 빈 가용시간 vs 위치 잡힌 일정이 같은 타입?). 두 레이어로 쪼갬: **coverage = 순수 "예약 받을 수 있는 시간" predicate**(머지된 시간 띠), **session = "위치·정원·사람 가진 한 일정"**. ① 결합은 **시간 포함 판정뿐**(coverage row id 는 머지/분할로 휘발성 → session 이 FK 로 참조 안 함, time containment 로만). ② coverage 는 항상 **머지·정규화**(10–12 + 12–14 → 10–14, `CoverageMerger`). ③ **원자 일정추가**(`POST /sessions`)가 coverage 확장+머지 → session find-or-create → hold 를 한 트랜잭션에 — 옛 "window 생성 + hold 추가" 2-call 폐기. ④ 정원이 window 에서 **session 으로 이전**(아래 PR #69 모델 그대로, 귀속 엔티티만 바뀜).
- **정원 = 계정 기본값 종속 + sparse override(2026-06-16, session 으로 이전)** — 정원은 "강사가 커버 가능한 인원" = `Account.defaultCapacity`(기본 4). 일정은 `capacityOverride==null` 이면 그 값을 **라이브 참조**(스냅샷·전파 write 없음 — 안 건드린 일정엔 숫자를 저장 안 하니 기본값만 바꾸면 됨), 그 날만 ±로 고정하면 override. 유효정원 = `override ?? account.defaultCapacity`(읽을 때 파생, `effectiveCapacity()`). 두 ± = 계정 baseline(PATCH `/settings`) / 일정 override(PATCH·DELETE `/sessions/{id}/capacity`). 2026-06-18 분리에서 `capacityOverride` 가 window→session 으로 그대로 옮겨졌다. [[availability-domain-concept]] 의 "정원" 절.
- **유효정원 < 점유 = 허용(확정 바닥)** — baseline/override 를 점유보다 낮춰도 막지 않는다. 이미 잡힌 점유(확정 enrollment·외부 hold)는 **유지**(취소 없음), 새 신청 수락만 차단(만석). 옛 "정원 자동확장"은 이 바닥 개념으로 흡수.
- **coverage 닫기가 session 가로지르면 거부 (`COVERAGE_HAS_SESSION` / -1014)** — BE 가 자동으로 일정을 정리하지 않는다(CS 유발). 식별 가능한 코드를 내려 FE 가 "내부 일정을 먼저 정리해주세요"로 유도. 예외 `CoverageHasSessionException`, i18n key `coverageHasSession`(`exception_ko.yml`). (포함/겹침 판정은 strict — 맞닿음은 충돌 아님.)
- **두 가지 정원 조정 = 단일 테이블** — ± 빠른조정과 외부예약을 같은 `AvailabilityHold` row 로(`memo` 로만 구분).
- **게이트 = 강사신청 보유(상태 무관)** — venue 와 동일 기조. 리뷰 대기 중에도 가용시간 준비 허용. → [[instructor-review-window-allows-prep]]. (가용시간은 강사 단위라 종목 조건 없음 — `existsByAccountId`.)
- **없음/비소유 = 400 통일**(`ResourceNotFoundException`) — 남의 일정 존재 숨김(venue 패턴).

## 안전망 테스트

`src/test/.../usecase/AvailabilityUseCaseTest` — 실 H2 + 시큐리티 체인(EmbeddedRedis 불필요). 그룹:
- **CV\*** = coverage 열기 머지 / 닫기 분할·축소 / session 보호(-1014).
- **SS\*** = session 원자 추가(coverage 자동확장) / 같은 (위치,시간) 누적 join / 활성 신청 있으면 삭제 거부.
- **CAL\*** = `?from&to` 두 레이어 분리 읽기(coverage[]+sessions[]).
- **C\*** = 정원(기본값 baseline·override·라이브 참조·확정 바닥).
- **G\*** = 게이트·인증, **V\*** = 검증, **R\*** = 권한·격리.

⚠️ `Authorization` 헤더는 **raw JWT**(Bearer prefix 없음). ⚠️ hold/coverage 는 LAZY/교체형 — 트랜잭션 밖 DB 확인은 `AvailabilityHoldJpaRepo.findBySessionId` / `AvailabilityCoverageJpaRepo`.

## enrollment 연동됨

- **풍덩 enrollment 연동 완료** — `pending`/`confirmed` 점유 + `applicants[]` 가 [enrollment](../enrollment/CLAUDE.md) 도메인에서 실데이터로 채워진다(`AvailabilityService.toResponse` 가 **session 별** 활성 enrollment 집계). 즉 **availability → enrollment(repo) 단방향 의존**(읽기 전용). 첫 신청이 (위치,블록) **session 을 생성**, 같은 (위치,블록)이면 그 session 에 join(별도 binder 없음 — session 이 생성 시점부터 위치를 소유).
- **강사 availability ∩ Venue 운영시간 교차 = 수강생 선택지** — enrollment 의 `EnrollmentOptionsService`·`BookableSlotDeriver` 가 구현(venue 부가 coverage 에 통째로 ⊆ 일 때만). → [[venue-domain-concept]].

## 아직 안 한 것 (후속 PR)
- **알림** — 대기 신청 배너의 푸시(notification outbox 연동) — enrollment 와 함께.
- **OFFICIAL venueRef 의 Sanity 서버사이드 읽기/캐시** — venue 동기화 설계 [[venue-sanity-sync-design]].
- **REST Docs `document(...)` 컨트롤러 테스트** — venue/course 와 동일하게 미작성(usecase 로 대체).
