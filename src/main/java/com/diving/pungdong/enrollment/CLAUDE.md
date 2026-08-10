# CLAUDE.md — enrollment (수강신청 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `Account`(student) · `Course` · `AvailabilitySession`/`AvailabilityCoverage`(`CoverageMerger`) · `venue`(VenueRefResolver/equipment) · `InstructorApplication`(강사 게이트) 를 단방향 참조.

## ⛔ 기저 원칙 — 일정 확정에는 강사의 수락이 무조건 필요하다

**어떤 경로로든 일정이 잡히거나 바뀌면 강사가 수락해야 확정된다.** 결제 여부·금액·회차 번호와 무관.

**왜**: 강사의 몸은 하나인데 **강사는 여러 플랫폼을 동시에 운영한다.** `AvailabilityCoverage` 는 강사가 우리에게 알려준 것일 뿐 타 플랫폼 예약·개인 일정을 반영하지 않는다 — **강사의 실제 일정은 우리에게 미지**다. coverage 가 열려 있다는 건 "아마 될 것"이지 "된다"가 아니다.

**유일한 예외**: 강사가 `proposeSlots` 로 **직접 낸 슬롯**을 학생이 `pickSlot` 으로 고르면 곧장 `CONFIRMED`. 강사가 가능한 시간을 제시한 것 자체가 동의이므로. (이 비대칭이 규칙의 핵심 — 나머지는 전부 `ACCEPT_PENDING`.)

⚠️ **실제로 놓친 적 있음**: 차액 결제(#204) 최초 구현이 "돈을 더 냈으니 강사 결정 대상이 아니다"라고 보고 곧장 슬롯을 바꿨다. **돈은 동의의 근거가 아니다.** 일정을 만들거나 바꾸는 코드를 손댈 때마다 이 표를 확인할 것:

| 누가 시작했나 | 결과 |
|---|---|
| `submit` · `scheduleNextRound` · `reschedule` · **차액 결제 슬롯 변경** | `ACCEPT_PENDING`(강사 결정 대기) + 24h 시계 |
| `pickSlot`(강사 제안 선택) | `CONFIRMED` 직행 |

## 무엇이 들어있나 — V2 booking 흐름

학생이 코스의 **첫 만남(1회차)**을 강사가 연 예약가능시간 안의 슬롯에 신청 → **즉시 결제(선결제)** → 강사 수락/거절(거절·무응답 자동환불). availability 의 풍덩 점유(`PENDING`/`CONFIRMED`/`applicants[]`)를 **실제로 채우고**, venue·availability 메모가 "venue 가 존재하는 궁극적 이유"라 한 **`강사 coverage(예약가능시간) ∩ Venue 운영블록 ∩ 코스 1회차 위치` 교집합**을 구현한다(venue 부가 coverage 에 통째로 ⊆ 일 때만).

- **컨트롤러**: `EnrollmentController`(`/enrollments/**` — 1회차 옵션·신청·**다음회차 옵션(`/{id}/next-options`)·2회차+ 신청(`POST /{id}/rounds`)·일정변경 선택(`/rounds/{rid}/pick-slot`)·직접 일정수정(`/rounds/{rid}/options`+`/reschedule`)**·내목록·**강의일정 hub**·취소, 학생), `InstructorEnrollmentController`(`/instructor/enrollments/**` — **수강관리 hub(`/hub`)**·받은 신청·수락·거절(전 회차)·**일정변경요청(`/{rid}/propose-slots`)·완료(`/{rid}/complete`, 세션 일괄 `/sessions/{sid}/complete`)**, 강사).
- **서비스**: `EnrollmentOptionsService`(교집합 — `getOptions`/`getNextOptions`), `EnrollmentService`(신청/2회차+/취소/일정변경 `pickSlot`/내목록 + 공유 `buildRound` + `mySchedule` hub), `InstructorEnrollmentService`(수락/거절/일정변경요청/**회차·세션 완료**/목록/**수강관리 hub** — 거래 단위[수강생×강의] 카드 파생: `InstructorEnrollmentStatus`/`InstructorRoundStatus`/`InstructorActionFlag` 저장X 파생, 학생 hub 거울), `RoundGate`(다음 schedulable 회차 — 순차 게이트), `EnrollmentExpiryService`(좌석 lock TTL 만료 + **세션일+24h 자동 완료 sweep**), `BookableSlotDeriver`(venue 운영블록).
- **다회차 진행·완료(PR2+3)**: 2회차+는 `RoundGate.nextSchedulable`(**직전 정규회차 done** 이면 열림 — done=CONFIRMED+doneAt, 정규 끝나면 EXTRA)이 정한 회차를 PENDING 으로 추가. 강사 **일정변경요청** = `EnrollmentRound.proposedSlots`(**완전한 슬롯 = 날짜+이용권+블록**, 위치 고정 — 날짜만 바꾸면 daypart 가 바뀌어 이용권·입장료·블록이 달라지므로) 채움 → 학생 `pickSlot` 가 그 중 하나 골라 **이미 결제된 회차 + 강사가 승인한 자리라 곧장 CONFIRMED**(세션 재결합+입장료 재산정, 싸졌으면 차액 자동환불). **완료(done)** = `doneAt`(강사 `completeRound`/`completeSession` 또는 세션일+24h 자동 sweep). hub `RoundScheduleStatus.DONE`/`CourseScheduleStatus.COMPLETED`(모든 정규 done — totalRounds 미달이면 PROGRESS) 파생. 거절은 **전 회차**(그 회차만 무효 + 전액 환불, 재신청으로 복구).
- **강의일정 hub** (`GET /enrollments/mine/schedule`): 내 신청을 강의(course) 단위로 그룹핑 + 진행상태 파생(`RoundScheduleStatus`/`CourseScheduleStatus` — EnrollmentStatus 매핑, **저장 X 파생값**). `ScheduleHubResponse{filters, courses[rounds]}`. 추가 조회 없이 enrollment 스냅샷만. 설계의 done/finalizing/completed/메모/세션채팅/일정변경/환불은 BE 미구현(로드맵) → 응답에 없음. 정책·갭·로드맵 = [docs/features/student-schedule.md](../../../../../../../docs/features/student-schedule.md).
- **엔티티 (다회차 2026-06-28)**: **`Enrollment`(수강 컨테이너 — student·course·**tuitionSnapshot**·createdAt·`rounds[]`)** ⊃ **`EnrollmentRound`(회차 — courseRound FK·roundIndex·roundKind·**availabilitySession**·venueRefId·date·block·ticketRef·status·**entry/equip/extra 스냅샷**·doneAt·rejectionReason)** ⊃ **`EnrollmentRoundEquipment`(itemRef·name·price·**size**)**. 수강료는 수강에 1번(1회차 결제에 전액), 부대비용은 회차별. 강의 상태는 회차들에서 파생(`RoundScheduleStatus`/`CourseScheduleStatus`). API 의 `{id}`·payment `enrollmentId` = **회차 id**. enum `EnrollmentStatus`(**5값** — PENDING(미결제)/**ACCEPT_PENDING**(결제완료·강사 결정 대기)/CONFIRMED/REJECTED/CANCELLED — `isActive()`/`occupiesCapacity()` + `ACTIVE`/`OCCUPYING` 집합 상수, done=CONFIRMED+doneAt). 슬롯·상태·점유 집계는 `EnrollmentRoundJpaRepo`.
- **레포**: `EnrollmentJpaRepo`(session별 집계·강사 코스별·내 목록).

## 핵심 모델 — "session 이 첫 신청으로 생성, 같은 (위치,블록)이면 join"

- enrollment 는 **`AvailabilitySession`(위치·시간블록·정원 단위)** 에 붙는다(`session_id`). 첫 신청이 그 (위치, 시간블록) session 을 **생성**하고(`findOrCreateSession`), 같은 **(venueRefId, blockStart, blockEnd)** 신청은 그 session 에 **join**. session 이 처음부터 위치를 소유하므로 bind/unbind 없음. 대신 **점유 0 = 일정 삭제**: 거절/취소로 활성 신청+hold 가 0 이 되면 `availability.SessionCleaner.deleteIfEmpty` 가 session 을 지운다. **단 enrollment 이력은 보존** — CANCELLED/REJECTED 는 안 지우고 `session_id` 만 끊음(스냅샷 date/위치/블록/가격/사유 남아 CS·환불 증빙). 외부 hold 제거(`removeHold`)도 점유 0 이면 같은 정리 → 204.
- **자격 = 블록이 강사 coverage(예약가능시간)에 통째로 ⊆**(`CoverageMerger.containsWhole`, 부분겹침 불가). 블록은 venue 운영 카탈로그의 이산 단위라 통째로만 선택.
- **만석(신청 시점 좌석 lock · 선착순, 2026-06-28)** = `활성(ACTIVE: 대기+결제완료+결제대기+확정) + 외부hold >= effectiveCapacity` 면 새 신청 거절. **PENDING 도 좌석을 잠근다**(옛 "하드캡 안 함" 폐기 — 소규모 정원 선착순). 수락은 잠긴 슬롯 전환만(정원 재검증 제거). `occupiesCapacity()`/`OCCUPYING`(=결제완료+결제대기+확정)은 캘린더 confirmed **표시 버킷** 전용 — 만석 판정은 `ACTIVE`.
- **동시성 하드닝(선결제=이중결제 방지, 2026-08-07)** — `EnrollmentService.requireSeat` 가 좌석 count 직전 세션 행을 **비관적 쓰기잠금**(`AvailabilitySessionJpaRepo.lockById` = SELECT … FOR UPDATE)으로 잡아 동시 신청 count+insert 를 직렬화 + `AvailabilitySession` 자연키 **UNIQUE**(`uk_availability_session_slot`, Flyway V12)로 "동시 새 세션 생성" 경합 차단. 왜/원리는 [enrollment.md](../../../../../../../docs/architecture/enrollment.md) §3-2.
- **미결제 재신청 = supersede(2026-08-11)** — 결제 전에 다른 날짜로 다시 신청하면 **새 회차를 만들지 않고 그 회차 슬롯을 갈아끼운다**(`swapSlot`). 안 그러면 `submit` 이 매번 새 `Enrollment` 를 만들어 좌석 점유가 쌓이고(1회차), 2회차+ 는 `RoundGate` 가 400 으로 막아 학생이 갇힌다. 스코프 = **(학생×강의×미결제 PENDING)** — `ACCEPT_PENDING`·확정·다른 강의는 절대 안 건드림. `submit` 은 1회차만, `POST /{id}/rounds` 는 **2회차+만**(1회차까지 잡으면 순차 게이트가 무력화된다).
  - 곁들여 고친 것: 옮기면 비워질 **내 옛 일정을 겹침 판정에서 제외**(`requireNoOverlap(..., ignoreSessionId)`) — 안 그러면 내 유령 점유가 나를 -1015 로 막는다. 같은 일정으로 되돌아가면 **만석 검사 생략**(이미 내가 점유 중).
- **lock 자동 만료(TTL)** = `EnrollmentExpiryService`(주기 스위프, 스케줄러 `EnrollmentExpiryScheduler` @Profile("!test")) — **선결제**: 미결제 PENDING `createdAt`+paymentTtlHours(12) 만료(환불 없음) / 결제완료 ACCEPT_PENDING `respondedAt`+pendingTtlHours(24) 만료 **+ 전액 자동환불**(`EnrollmentRefundRequestedEvent`→payment). **두 상태뿐**(전 회차 통일). CANCELLED + `SessionCleaner` 좌석 해제. TTL 은 `SiteSettings`(Sanity 런타임). 각 건 자기 트랜잭션. 만료 알림은 후속.
- **availability 캘린더 연동**: `availability/AvailabilityService.toResponse` 가 **session별** enrollment 를 집계해 `confirmedCount`/`pendingCount`/`applicants[]` 를 채운다 → **availability → enrollment(repo) 단방향 의존**(읽기 전용). 5상태 모델 실가동.

## 작업 전 반드시 읽기

- **[docs/features/booking.md](../../../../../../../docs/features/booking.md)** — 정책·왜·히스토리(교집합·exact-match·결제후확정·첫만남만). **여기부터.**
- **[docs/architecture/enrollment.md](../../../../../../../docs/architecture/enrollment.md)** — 흐름/ER/권한 매트릭스/간극
- **[availability/CLAUDE.md](../availability/CLAUDE.md)** · **[[availability-domain-concept]]** — coverage(예약가능시간)/session(일정) 2층 모델
- **[venue/CLAUDE.md](../venue/CLAUDE.md)** · **[[venue-domain-concept]]** — `VenueRefResolver`·운영시간(daypart·timeBlock)·교집합
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **다회차 재설계 (2026-06-28)** — 옛 "첫 만남(1회차)만, Enrollment=단일 슬롯"은 v1 축소였다. 자격과정은 주1회×N회가 본 모델이라 **`Enrollment(수강) ⊃ EnrollmentRound(회차) ⊃ RoundEquipment`** 로 분할(붕어빵: Course=틀). 슬롯·상태·부대비용이 회차로 내려가고 수강은 묶음+수강료 보유. PR1=엔티티 분할+1회차 흐름 보존(pay-first), 2회차+ 진행·완료·환불은 후속 PR. **정책·왜·액션매트릭스·환불율은 [docs/features/booking.md](../../../../../../../docs/features/booking.md)·[payment.md](../../../../../../../docs/features/payment.md)**.
- **선결제 (2026-08-07 도입 → 2026-08-09 전 회차 통일)** — **모든 회차가 신청 즉시 결제**: 신청 `PENDING`(미결제·좌석 점유) → 결제 → `ACCEPT_PENDING`(결제완료·강사 결정 대기) → 강사 수락 `CONFIRMED` / 거절·무응답 24h `REJECTED`·`CANCELLED` + **전액 자동환불**(`EnrollmentRefundRequestedEvent`→payment `EnrollmentRefundListener`→`RefundService.refundRoundFully`, 동기·롤백안전). 미결제 12h 만료(환불 없음). 카드사 심사가 익숙한 "주문 즉시 결제" 표준으로 바꿔 심사 리스크↓ + 어차피 붙일 방향.
  - **강사 결정은 3지선다** — 수락 / 거절 / **일정조정 제안**(`proposeSlots`, ACCEPT_PENDING 에서만). 결제가 앞으로 당겨졌으니 제안도 결제 <b>후</b> 시점이 되고, 학생은 결제가 아니라 **ㅇㅋ**(`pickSlot`→CONFIRMED) / **ㄴㄴ**(`cancel`→전액환불, 또는 `reschedule`로 내 슬롯 재제안)만 한다.
  - **금액 불변식** — "그 회차에 남은 결제 순액 == `chargeTotal()`". 결제 후 슬롯 변경은 **금액이 늘면 400**(더 비싼 슬롯은 취소 후 재신청), 줄면 `EnrollmentPartialRefundRequestedEvent`로 차액 자동환불. 그래서 payment 조회 없이 변경 전 `chargeTotal()`이 곧 결제액이다.
  - **거절/취소는 그 회차만** — 수강은 살아 있고 `RoundGate`가 자리를 비우므로 학생이 **그 회차를 다른 날짜로 다시 신청**할 수 있다(시간 제한 없음). hub 파생은 같은 회차에 더 최근 활성/완료 행이 있으면 죽은 행을 무시한다(안 그러면 강의가 영구 RESCHEDULING).
  - 상태기계·왜는 [enrollment.md](../../../../../../../docs/architecture/enrollment.md) §3-2 · [payment.md](../../../../../../../docs/features/payment.md).
- **옛 pay-first (2026-06-28, 재정의됨)** — "강사가 수영장을 결제 *이후에* 예약(풀부킹이 결제 뒤)". 그 통찰(돈 확보 후 풀 잡기, 풀부킹 실패 시 전액 무료 환불)은 유효하고, 선결제로 결제 시점이 강사 수락보다도 앞(신청 시점)으로 더 당겨졌다. 수강료=enrollment 스냅샷 고정(라이브 폐기).
- ~~**첫 만남(1회차)만 신청**~~ — (다회차 재설계로 대체) 디자인 "나머지 일정은 수강하면서 결정".
- **session-bound 모델 (2026-06-18 분리 반영)** — exact-match join 을 구조적으로 떨어뜨림(사용자 결정: "같은 venue·정확히 같은 시간대만 합류, 부분겹침 불가"). enrollment 는 `AvailabilitySession`(위치·블록·정원 단위)에 붙고, 슬롯 식별자는 `(date, venueRefId, blockStart, blockEnd)`. 첫 신청이 session 을 생성, 같은 (위치,블록)이면 join. 자격은 그 블록이 강사 coverage 에 통째로 ⊆ 일 때만. (옛 `availabilityWindowId` → `date` + 위치 + 블록으로 바뀜; `WindowBinder` 제거.)
- **교집합 = 평탄 슬롯** — UX(날짜→위치→시간)와 계산순서 분리. BE 가 `availability ∩ venue 운영블록 ∩ 코스 위치`를 평탄 `slots[]` 로 계산, FE 가 그룹핑.
- **가격 스냅샷** — 신청 시점 추정치(tuition/entry/equipment)를 박음. 권위 금액은 강사 확정/결제 재계산(후속).
- **게이트** — 학생 신청은 **로그인 + 본인인증(휴대폰 SMS) 선행**(2026-07-08). 정책: 수강생은 수강신청 전, 강사는 강사 전환 전에 본인인증. `submit()` 이 세션 계정의 최신 VERIFIED 를 조회(강사 신청과 동일 진실원 = `GET /identity-verifications/me` 쿼리)해 없으면 **403 `IdentityVerificationRequiredException`(-1017)** → FE 가 본인인증 화면으로 분기(만석·잘못된 입력 400 과 구분). 2회차+ 는 그 수강을 전제로 하니 전이적 커버(무만료). 강사 측(`/instructor/enrollments`)은 강사신청 보유(venue/availability 기조). 없음/비소유 = 400(존재 숨김).
- **대여 장비 표시 = 공유 `GearItem` 하나 (2026-07-06)** — `EnrollmentRoundEquipment`(name·size) 스냅샷을 표시용으로 투영한 `{name, sizeLabel}` 뷰. 강사 hub(`InstructorScheduleHubResponse`)·학생 hub(`ScheduleHubResponse`)·강사 캘린더 신청자행(`availability.ApplicantSummaryResponse.gear`) **셋이 같은 소스라 형태가 갈라지면 안 됨** → nested 3벌 폐기하고 **`enrollment/dto/GearItem` 하나로 통합**(스냅샷 주인=enrollment 소유, availability 는 이미 enrollment 단방향 참조라 재사용). `sizeLabel` 은 저장값 그대로, 단위는 FE. **"대여장비를 별도 도메인으로?" — 지금은 아님**(사용자 토의): 장비는 카탈로그(`venue/equipment`)+예약스냅샷(enrollment)로 이미 올바르게 나뉘고 독립 생명주기가 없음. **재고·유닛반납·대여정산** 요구가 생기면 그때 `equipment`/`rental` 도메인으로 추출(트리거 명시).

## 안전망 테스트

`src/test/.../usecase/EnrollmentUseCaseTest` — 실 H2 + 시큐리티 체인(EmbeddedRedis 불필요). O(옵션 교집합)/S(신청)/J(합류 exact-match)/F(만석)/A(수락·거절)/C(취소)/G·R(게이트·권한). ⚠️ `Authorization` raw JWT. enrollment·session 은 LAZY — 트랜잭션 밖 DB 확인은 repo. 자격은 강사 coverage 를 먼저 열어야(또는 강사 일정 추가로) 통과한다.

## 아직 안 한 것 (후속 PR)

- **정산** — 수수료 분해(PG 3.4% + 플랫폼 6.6%). (결제 자체는 [payment](../payment/CLAUDE.md) 로 연동 완료 — 선결제: 신청→결제(ACCEPT_PENDING)→수락(CONFIRMED)/거절·만료 자동환불.)
- ~~장비 사이즈 캡처~~ **완료** — 신청 요청 `equipmentSizes`(itemRef→"270"/"L", `EnrollmentCreateRequest`·`RoundScheduleRequest`) → `addEquipment` 가 그 품목 `sizeOptions` 멤버십 검증 후 `EnrollmentRoundEquipment.size` 스냅샷 저장 → 강사 hub `gearItems.sizeLabel` 로 노출. 사이즈 없는 품목/미선택은 null. 프리셋 밖 = 400(자유입력 차단).
- 세션 단체채팅/공지 · enrollment-management 강사 검토 시트 풀 UI.
- **다회차 진행 중 일정 결정**(2회차+) · 환불/재일정 상태기계.
- venue 운영 **MONTHLY 휴무·OPEN 정밀 슬롯화** 정밀도 · 공휴일.
- REST Docs `document(...)` 컨트롤러 테스트(venue/course/availability 와 동일하게 use-case 로 대체).
