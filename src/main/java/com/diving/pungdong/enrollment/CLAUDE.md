# CLAUDE.md — enrollment (수강신청 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `Account`(student) · `Course` · `AvailabilityWindow` · `venue`(VenueRefResolver/equipment) · `InstructorApplication`(강사 게이트) 를 단방향 참조.

## 무엇이 들어있나 — V2 booking 흐름

학생이 코스의 **첫 만남(1회차)**을 강사가 연 가용시간 슬롯에 신청 → 강사 답변 대기 → 수락/거절. availability v1 이 비워둔 점유(`PENDING`/`CONFIRMED`/`applicants[]`)를 **실제로 채우고**, venue·availability 메모가 "venue 가 존재하는 궁극적 이유"라 한 **`강사 availability ∩ Venue 운영시간` 교집합**을 처음 구현한다.

- **컨트롤러**: `EnrollmentController`(`/enrollments/**` — 옵션·신청·내목록·취소, 학생), `InstructorEnrollmentController`(`/instructor/enrollments/**` — 받은 신청·수락·거절, 강사).
- **서비스**: `EnrollmentOptionsService`(교집합 슬롯 계산), `EnrollmentService`(신청/취소/내목록), `InstructorEnrollmentService`(수락/거절/목록), `BookableSlotDeriver`(venue 운영블록 도출 — 옵션·신청 검증 공유), `WindowBinder`(window bind/unbind).
- **엔티티**: `Enrollment`(student·course·roundIndex·availabilityWindow·venueRefId·blockStart/End·ticketRef·status·가격 스냅샷) → `EnrollmentEquipment`(장비 스냅샷). enum `EnrollmentStatus`(PENDING/CONFIRMED/REJECTED/CANCELLED).
- **레포**: `EnrollmentJpaRepo`(window별 집계·강사 코스별·내 목록).

## 핵심 모델 — "window 가 첫 신청으로 bound"

- enrollment 는 **`AvailabilityWindow`(capacity 단위)** 에 붙는다. 첫 active enrollment 가 window 를 (venueRefId, sessionLabel) 로 bind(`WindowBinder`) → 이후 신청은 **같은 venue + 정확히 같은 블록**(`blockStart`/`blockEnd`)만 합류. **부분겹침 구조적 불가**(블록은 venue 운영 카탈로그의 이산 단위, 통째로만 선택).
- **만석** = `confirmed + 외부hold >= capacity` 일 때만 새 신청 거절. **PENDING 은 하드캡 안 함**(여러 건 쌓여도 강사가 수락/거절로 정리). 수락 시 정원 재검증.
- 활성(PENDING/CONFIRMED) 0 + hold 0 → window unbind(다시 available).
- **availability 캘린더 연동**: `availability/AvailabilityService.toResponse` 가 window별 enrollment 를 집계해 `confirmedCount`/`pendingCount`/`applicants[]` 를 채운다 → **availability → enrollment(repo) 단방향 의존**(읽기 전용). 5상태 모델 실가동.

## 작업 전 반드시 읽기

- **[docs/features/booking.md](../../../../../../../docs/features/booking.md)** — 정책·왜·히스토리(교집합·exact-match·결제후확정·첫만남만). **여기부터.**
- **[docs/architecture/enrollment.md](../../../../../../../docs/architecture/enrollment.md)** — 흐름/ER/권한 매트릭스/간극
- **[availability/CLAUDE.md](../availability/CLAUDE.md)** · **[[availability-domain-concept]]** — window/점유 2층 모델
- **[venue/CLAUDE.md](../venue/CLAUDE.md)** · **[[venue-domain-concept]]** — `VenueRefResolver`·운영시간(daypart·timeBlock)·교집합
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **첫 만남(1회차)만 신청** — 디자인 "나머지 일정은 수강하면서 결정". 나머지 회차 일정 결정은 후속.
- **신청 시 결제 없음** — 디자인 "강사 확정 후 결제 링크 푸시". v1 은 수락=CONFIRMED(결제 단계 후속). 정산 수수료(PG 3.4% + 플랫폼 6.6%, 실비 0%)도 후속.
- **window-bound 모델** — exact-match join 을 구조적으로 떨어뜨림(사용자 결정: "같은 venue·정확히 같은 시간대만 합류, 부분겹침 불가"). 1 window = 1 세션 단순화(하루 2세션은 window 2개).
- **교집합 = 평탄 슬롯** — UX(날짜→위치→시간)와 계산순서 분리. BE 가 `availability ∩ venue 운영블록 ∩ 코스 위치`를 평탄 `slots[]` 로 계산, FE 가 그룹핑.
- **가격 스냅샷** — 신청 시점 추정치(tuition/entry/equipment)를 박음. 권위 금액은 강사 확정/결제 재계산(후속).
- **게이트** — 학생 신청은 인증만(누구나 OPEN 코스 신청). 강사 측(`/instructor/enrollments`)은 강사신청 보유(venue/availability 기조). 없음/비소유 = 400(존재 숨김).

## 안전망 테스트

`src/test/.../usecase/EnrollmentUseCaseTest` — 실 H2 + 시큐리티 체인(EmbeddedRedis 불필요). O(옵션 교집합)/S(신청)/J(합류 exact-match)/F(만석)/A(수락·거절)/C(취소)/G·R(게이트·권한). ⚠️ `Authorization` raw JWT. enrollment·window 는 LAZY — 트랜잭션 밖 DB 확인은 repo.

## 아직 안 한 것 (후속 PR)

- **결제(PG)·정산** — 수락 후 결제링크 푸시·결제완료=확정·수수료 분해.
- **장비 사이즈 캡처**(핀 mm·슈트 S~XL) · 세션 단체채팅/공지 · enrollment-management 강사 검토 시트 풀 UI.
- **다회차 진행 중 일정 결정**(2회차+) · 환불/재일정 상태기계 · 넓은 window 다중세션 분할(현재 1window=1세션).
- venue 운영 **MONTHLY 휴무·OPEN 정밀 슬롯화** 정밀도 · 공휴일.
- REST Docs `document(...)` 컨트롤러 테스트(venue/course/availability 와 동일하게 use-case 로 대체).
