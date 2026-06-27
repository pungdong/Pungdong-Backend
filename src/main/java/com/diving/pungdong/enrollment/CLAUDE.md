# CLAUDE.md — enrollment (수강신청 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `Account`(student) · `Course` · `AvailabilitySession`/`AvailabilityCoverage`(`CoverageMerger`) · `venue`(VenueRefResolver/equipment) · `InstructorApplication`(강사 게이트) 를 단방향 참조.

## 무엇이 들어있나 — V2 booking 흐름

학생이 코스의 **첫 만남(1회차)**을 강사가 연 예약가능시간 안의 슬롯에 신청 → 강사 답변 대기 → 수락/거절. availability 의 풍덩 점유(`PENDING`/`CONFIRMED`/`applicants[]`)를 **실제로 채우고**, venue·availability 메모가 "venue 가 존재하는 궁극적 이유"라 한 **`강사 coverage(예약가능시간) ∩ Venue 운영블록 ∩ 코스 1회차 위치` 교집합**을 구현한다(venue 부가 coverage 에 통째로 ⊆ 일 때만).

- **컨트롤러**: `EnrollmentController`(`/enrollments/**` — 1회차 옵션·신청·**다음회차 옵션(`/{id}/next-options`)·2회차+ 신청(`POST /{id}/rounds`)·일정변경 선택(`/rounds/{rid}/pick-date`)**·내목록·**강의일정 hub**·취소, 학생), `InstructorEnrollmentController`(`/instructor/enrollments/**` — 받은 신청·수락·거절(1회차만)·**일정변경요청(`/{rid}/propose-dates`)**, 강사).
- **서비스**: `EnrollmentOptionsService`(교집합 슬롯 — `getOptions`(1회차)/`getNextOptions`(다음회차)), `EnrollmentService`(신청/2회차+ 신청/취소/일정변경 pick/내목록 + **공유 `buildRound`** + `mySchedule` hub), `InstructorEnrollmentService`(수락/거절/일정변경요청/목록), `RoundGate`(다음 schedulable 회차 — 순차 게이트, 신청·옵션 공유), `BookableSlotDeriver`(venue 운영블록), `EnrollmentExpiryService`(좌석 lock TTL 만료).
- **다회차 진행(PR2)**: 2회차+는 `RoundGate.nextSchedulable`(직전 정규 CONFIRMED 면 열림, 정규 끝나면 EXTRA)이 정한 회차를 PENDING 으로 추가. 강사 **일정변경요청** = `EnrollmentRound.proposedDates`(같은 위치/이용권/블록, 날짜만 대안 — 서버 검증) 채움 → 학생 `pickDate` 가 그 중 하나 골라 **사전 수락이라 곧장 PAYMENT_PENDING**(날짜 바뀌면 세션 재결합+입장료 재산정). 거절은 1회차(`isFirstMeeting`)만. 게이트 신호=CONFIRMED, done 추적 후 done 으로 강화(PR3).
- **강의일정 hub** (`GET /enrollments/mine/schedule`): 내 신청을 강의(course) 단위로 그룹핑 + 진행상태 파생(`RoundScheduleStatus`/`CourseScheduleStatus` — EnrollmentStatus 매핑, **저장 X 파생값**). `ScheduleHubResponse{filters, courses[rounds]}`. 추가 조회 없이 enrollment 스냅샷만. 설계의 done/finalizing/completed/메모/세션채팅/일정변경/환불은 BE 미구현(로드맵) → 응답에 없음. 정책·갭·로드맵 = [docs/features/student-schedule.md](../../../../../../../docs/features/student-schedule.md).
- **엔티티 (다회차 2026-06-28)**: **`Enrollment`(수강 컨테이너 — student·course·**tuitionSnapshot**·createdAt·`rounds[]`)** ⊃ **`EnrollmentRound`(회차 — courseRound FK·roundIndex·roundKind·**availabilitySession**·venueRefId·date·block·ticketRef·status·**entry/equip/extra 스냅샷**·doneAt·rejectionReason)** ⊃ **`EnrollmentRoundEquipment`(itemRef·name·price·**size**)**. 수강료는 수강에 1번(1회차 결제에 전액), 부대비용은 회차별. 강의 상태는 회차들에서 파생(`RoundScheduleStatus`/`CourseScheduleStatus`). API 의 `{id}`·payment `enrollmentId` = **회차 id**. enum `EnrollmentStatus`(PENDING/**PAYMENT_PENDING**/CONFIRMED/REJECTED/CANCELLED — `isActive()`/`occupiesCapacity()` + `ACTIVE`/`OCCUPYING` 집합 상수, done=CONFIRMED+doneAt). 슬롯·상태·점유 집계는 `EnrollmentRoundJpaRepo`.
- **레포**: `EnrollmentJpaRepo`(session별 집계·강사 코스별·내 목록).

## 핵심 모델 — "session 이 첫 신청으로 생성, 같은 (위치,블록)이면 join"

- enrollment 는 **`AvailabilitySession`(위치·시간블록·정원 단위)** 에 붙는다(`session_id`). 첫 신청이 그 (위치, 시간블록) session 을 **생성**하고(`findOrCreateSession`), 같은 **(venueRefId, blockStart, blockEnd)** 신청은 그 session 에 **join**. session 이 처음부터 위치를 소유하므로 bind/unbind 없음. 대신 **점유 0 = 일정 삭제**: 거절/취소로 활성 신청+hold 가 0 이 되면 `availability.SessionCleaner.deleteIfEmpty` 가 session 을 지운다. **단 enrollment 이력은 보존** — CANCELLED/REJECTED 는 안 지우고 `session_id` 만 끊음(스냅샷 date/위치/블록/가격/사유 남아 CS·환불 증빙). 외부 hold 제거(`removeHold`)도 점유 0 이면 같은 정리 → 204.
- **자격 = 블록이 강사 coverage(예약가능시간)에 통째로 ⊆**(`CoverageMerger.containsWhole`, 부분겹침 불가). 블록은 venue 운영 카탈로그의 이산 단위라 통째로만 선택.
- **만석(신청 시점 좌석 lock · 선착순, 2026-06-28)** = `활성(ACTIVE: 대기+결제대기+확정) + 외부hold >= effectiveCapacity` 면 새 신청 거절. **PENDING 도 좌석을 잠근다**(옛 "하드캡 안 함" 폐기 — 소규모 정원 선착순). 수락은 잠긴 슬롯 전환만(정원 재검증 제거). `occupiesCapacity()`/`OCCUPYING`(=결제대기+확정)은 이제 캘린더 confirmed **표시 버킷** 전용 — 만석 판정은 `ACTIVE`.
- **lock 자동 만료(TTL)** = `EnrollmentExpiryService`(주기 스위프, 스케줄러는 `EnrollmentExpiryScheduler` @Profile("!test")) — PENDING `createdAt`+pendingTtlHours(24) / PAYMENT_PENDING `respondedAt`+paymentTtlHours(12) 지나면 CANCELLED + `SessionCleaner` 좌석 해제. TTL 값은 `SiteSettings`(Sanity 런타임). 각 건 자기 트랜잭션. 만료 알림은 후속(notification 미연동).
- **availability 캘린더 연동**: `availability/AvailabilityService.toResponse` 가 **session별** enrollment 를 집계해 `confirmedCount`/`pendingCount`/`applicants[]` 를 채운다 → **availability → enrollment(repo) 단방향 의존**(읽기 전용). 5상태 모델 실가동.

## 작업 전 반드시 읽기

- **[docs/features/booking.md](../../../../../../../docs/features/booking.md)** — 정책·왜·히스토리(교집합·exact-match·결제후확정·첫만남만). **여기부터.**
- **[docs/architecture/enrollment.md](../../../../../../../docs/architecture/enrollment.md)** — 흐름/ER/권한 매트릭스/간극
- **[availability/CLAUDE.md](../availability/CLAUDE.md)** · **[[availability-domain-concept]]** — coverage(예약가능시간)/session(일정) 2층 모델
- **[venue/CLAUDE.md](../venue/CLAUDE.md)** · **[[venue-domain-concept]]** — `VenueRefResolver`·운영시간(daypart·timeBlock)·교집합
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **다회차 재설계 (2026-06-28)** — 옛 "첫 만남(1회차)만, Enrollment=단일 슬롯"은 v1 축소였다. 자격과정은 주1회×N회가 본 모델이라 **`Enrollment(수강) ⊃ EnrollmentRound(회차) ⊃ RoundEquipment`** 로 분할(붕어빵: Course=틀). 슬롯·상태·부대비용이 회차로 내려가고 수강은 묶음+수강료 보유. PR1=엔티티 분할+1회차 흐름 보존(pay-first), 2회차+ 진행·완료·환불은 후속 PR. **정책·왜·액션매트릭스·환불율은 [docs/features/booking.md](../../../../../../../docs/features/booking.md)·[payment.md](../../../../../../../docs/features/payment.md)**.
- **pay-first (2026-06-28)** — 수락→**결제(CONFIRMED)**→강사 수영장 예약. 옛 "풀 먼저 예약 후 수락"은 미결제 시 강사 수영장 패널티 구멍이라 폐기. 무료취소 경계 = 결제(PENDING·PAYMENT_PENDING 까지 무료, cancel 이 PAYMENT_PENDING 도 허용). 수강료=enrollment 스냅샷 고정(라이브 폐기).
- ~~**첫 만남(1회차)만 신청**~~ — (위 재설계로 대체) 디자인 "나머지 일정은 수강하면서 결정".
- **수락 → 결제 → 확정 (2026-06-26 결제 연동)** — 디자인 "강사 확정 후 결제 링크 푸시". 수락은 `PAYMENT_PENDING`(결제 대기·슬롯 점유), 결제 승인이 `CONFIRMED` 로 넘긴다. 결제는 [payment 도메인](../payment/CLAUDE.md)(토스 결제위젯) — [docs/features/payment.md](../../../../../../../docs/features/payment.md). 정산 수수료(PG 3.4% + 플랫폼 6.6%, 실비 0%)는 후속.
- **session-bound 모델 (2026-06-18 분리 반영)** — exact-match join 을 구조적으로 떨어뜨림(사용자 결정: "같은 venue·정확히 같은 시간대만 합류, 부분겹침 불가"). enrollment 는 `AvailabilitySession`(위치·블록·정원 단위)에 붙고, 슬롯 식별자는 `(date, venueRefId, blockStart, blockEnd)`. 첫 신청이 session 을 생성, 같은 (위치,블록)이면 join. 자격은 그 블록이 강사 coverage 에 통째로 ⊆ 일 때만. (옛 `availabilityWindowId` → `date` + 위치 + 블록으로 바뀜; `WindowBinder` 제거.)
- **교집합 = 평탄 슬롯** — UX(날짜→위치→시간)와 계산순서 분리. BE 가 `availability ∩ venue 운영블록 ∩ 코스 위치`를 평탄 `slots[]` 로 계산, FE 가 그룹핑.
- **가격 스냅샷** — 신청 시점 추정치(tuition/entry/equipment)를 박음. 권위 금액은 강사 확정/결제 재계산(후속).
- **게이트** — 학생 신청은 인증만(누구나 OPEN 코스 신청). 강사 측(`/instructor/enrollments`)은 강사신청 보유(venue/availability 기조). 없음/비소유 = 400(존재 숨김).

## 안전망 테스트

`src/test/.../usecase/EnrollmentUseCaseTest` — 실 H2 + 시큐리티 체인(EmbeddedRedis 불필요). O(옵션 교집합)/S(신청)/J(합류 exact-match)/F(만석)/A(수락·거절)/C(취소)/G·R(게이트·권한). ⚠️ `Authorization` raw JWT. enrollment·session 은 LAZY — 트랜잭션 밖 DB 확인은 repo. 자격은 강사 coverage 를 먼저 열어야(또는 강사 일정 추가로) 통과한다.

## 아직 안 한 것 (후속 PR)

- **정산** — 수수료 분해(PG 3.4% + 플랫폼 6.6%). (결제 자체는 [payment](../payment/CLAUDE.md) 로 연동 완료 — 수락→결제대기→확정.)
- **장비 사이즈 캡처**(핀 mm·슈트 S~XL) · 세션 단체채팅/공지 · enrollment-management 강사 검토 시트 풀 UI.
- **다회차 진행 중 일정 결정**(2회차+) · 환불/재일정 상태기계.
- venue 운영 **MONTHLY 휴무·OPEN 정밀 슬롯화** 정밀도 · 공휴일.
- REST Docs `document(...)` 컨트롤러 테스트(venue/course/availability 와 동일하게 use-case 로 대체).
