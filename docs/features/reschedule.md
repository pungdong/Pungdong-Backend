# 일정변경 (reschedule / propose-slots)

> **피처 문서** — 정책·왜·히스토리를 소유. 구현(엔드포인트·세션·hold·교집합)은 [docs/architecture/enrollment.md](../architecture/enrollment.md) · [availability.md](../architecture/availability.md) 로 링크만. 수강신청 전반 정책은 [booking.md](booking.md).

## 한 줄

이미 잡힌 회차의 슬롯을 **취소가 아니라 제자리에서 바꾸는** 흐름. 두 진입점: **강사 제안**(`propose-slots` → 학생 `pick-slot`)과 **학생 직접 수정**(`reschedule`). 옛 슬롯은 이력으로 적재되고 회차 id 는 유지된다.

## 협력 도메인

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| enrollment | [enrollment.md](../architecture/enrollment.md) | 회차(EnrollmentRound)·proposedSlots·pick/reschedule·상태전이 (이 피처) |
| availability | [availability.md](../architecture/availability.md) | session(정원 단위)·좌석 lock·**좌석 hold**·점유 0 정리 |
| venue | [venue.md](../architecture/venue.md) | 운영 시간블록·daypart 입장료(날짜 바뀌면 재산정) |
| payment | [payment.md](payment.md) | pick 후 PAYMENT_PENDING → 결제 |

## 정책 (requirements)

### 두 진입점의 차이

| | **강사 제안** (`propose-slots` → `pick-slot`) | **학생 직접** (`reschedule`) |
|---|---|---|
| 누가 | 강사가 대안 슬롯 N개 제시 → 학생이 그중 택1 | 학생이 원하는 슬롯으로 직접 변경 |
| 위치 | **회차에 고정** (날짜만 바뀜) | **변경 가능** (날짜 따라 위치가 달라질 수 있음) |
| 장비 | **원 회차 그대로** | **재선택 가능** |
| 슬롯 필드 | `{date, ticketRef, blockStart, blockEnd}` | `{date, venueRefId, ticketRef, blockStart, blockEnd, equipmentRefs[]}` |
| 결과 상태 | 학생 pick → **PAYMENT_PENDING** (강사 사전수락) | **PENDING** (강사 재수락 필요) |

- **위치 고정인 이유**: 위치가 같아도 **날짜가 바뀌면 그 날짜 daypart 기준으로 이용권·입장료·운영블록이 달라짐** → 슬롯은 "날짜+이용권+블록" 완전체로 제안. 위치까지 바꾸려면 학생 `reschedule` 경로.
- **둘 다 취소가 아님** — 회차 유지, 옛 슬롯은 `slotHistory` 적재(CS/증빙). PENDING 회차에만 가능.

### 좌석 정원 — 하드캡, 절대 초과 불가

**프리다이빙/스쿠버는 수영장 정책상 강사당 최대 인원이 물리적·법적 하드캡**이다. 따라서 `requireSeat`(만석 = `활성+hold >= 유효정원`) 게이트는 **어떤 경로에서도 우회하지 않는다**. 일정변경으로 옮긴 슬롯이 만석이면 그 이동은 불가능 — "한 명 더 끼워넣기"는 정원을 명시적으로 올리지 않는 한 허용 안 함.

### 강사 제안 = 좌석 보장 (hold-and-guarantee) ✅ 구현됨

> **방향 결정(C), 2026-06-28 · 구현 완료.** 메모 [[propose-slots-hold-guarantee]].

하드캡이라 "강사가 제안한 자리는 학생이 고를 때 반드시 잡힌다"를 보장하려면 **제안 시점에 좌석을 미리 잡아두는 수밖에 없다**(`requireSeat` 우회는 하드캡 위반이라 불가). 그래서:

- **propose 시 슬롯별로 좌석 hold** (회차 귀속, expiresAt). 못 잡는 만석 슬롯은 제안에서 제외(하나도 못 잡으면 400).
- hold 동안 다른 학생 신청은 `requireSeat` 가 hold 를 세서 **정상적으로 막힘**(보장이 작동하는 것). 학생은 옛 좌석(1) + 제안(N) = 한시적 1+N석 점유.
- **pick = 항상 성공**: 고른 슬롯 hold→실점유 전환, 나머지 N-1 + 옛 좌석 release(빈 session `SessionCleaner` 정리) → PAYMENT_PENDING.
- **제안 슬롯 시스템 강제 max 3** — hold 를 거니 "한 명이 잠그는 좌석 수" 상한. 강사도 보통 3개면 충분, 학생도 3개 이내가 고르기 편함.
- **proposalTtlHours = 6h** (신설, 결제 `paymentTtlHours`=12h 와 분리). 제안 결정 윈도우 = hold 가 다른 학생을 막는 시간이라 짧게. 만료 sweep(`EnrollmentExpiryService.sweepExpiredProposals`) = hold release + proposedSlots clear + 회차는 평범한 PENDING(취소 아님 → hub 에서 RESCHEDULING→WAITING, 강사 재제안 가능). 값은 SiteSettings(Sanity) 런타임.
- **캘린더에 hold 도 "제안중"으로 반영** — `HoldResponse.kind='PROPOSAL'`, 잔여 인원에 자동 반영(올바른 표시).
- **강사 옵션 엔드포인트** `GET /instructor/enrollments/{roundId}/propose-options` (학생 round options 대칭, `remaining/full` 포함) — 강사가 만석 슬롯을 애초에 안 고르게.

**구현 메커니즘** (도메인 문서 [enrollment.md](../architecture/enrollment.md)·[availability.md](../architecture/availability.md)에 상세): 별도 ProposalHold 테이블이 아니라 **기존 `AvailabilityHold` 를 재사용**(`proposalRoundId`·`expiresAt` nullable 컬럼 추가, V3 마이그레이션). 이유 — `heldCount()` 가 모든 hold 를 합산하므로 제안 hold 가 만석 판정·캘린더 잔여·`requireSeat` **모든 곳에서 자동으로** 반영된다. 별도 테이블이면 점유 계산 여러 곳에 일일이 끼워야 하고 한 곳만 빠뜨려도 하드캡이 조용히 깨지는데, 재사용은 그 위험이 구조적으로 없음(`proposalRoundId` 는 raw Long — availability→enrollment 역참조 회피).

**왜 C(보장) 인가**: 초기 플랫폼 신뢰 > over-reservation 비용. 폭발적 경합 시장이 아니라 선점 비용이 실제로 낮음. 불편이 관측되면 **(A) 표시-only**(좌석 hold 없이 `full` 표시 + pick 재확인, 가끔 재선택)로 완화 — A↔C 는 깨끗한 전환 경로.

## 결정 히스토리

| 시점 | 결정 | 비고 |
|---|---|---|
| 2026-06-28 | reschedule(학생 직접) — 취소 아닌 제자리 변경 + 슬롯 이력 | PR #108 |
| 2026-06-28 | propose-slots(강사 제안) → pick-slot(학생 택1) → PAYMENT_PENDING | 강사 hub #109 |
| 2026-06-28 | **정원 처리 = (C) hold-and-guarantee** (max 3 · proposalTtlHours 6h · 캘린더 hold 표시 · 강사 옵션 엔드포인트). 하드캡 우회 금지, 보장은 사전 hold 로 | ✅ 구현 |
| 2026-06-28 | 구현 = 별도 테이블 대신 `AvailabilityHold` 재사용(heldCount 자동 합산으로 하드캡 한 곳 계산). V3 마이그레이션 | ✅ |

## 미해결 / 확장

- 🟡 만료/제안 푸시 알림 (notification 미연동, booking 만료 알림과 묶음).
- 🟡 **테스트 env 누출** — `PAYMENT_MODE=toss`(direnv)가 test JVM 으로 새면 RealTossPaymentClient 가 켜져 RefundUseCaseTest 가 로컬에서 400. 이 PR 범위 밖이지만 `application-test.yml` 에 `pungdong.payment.mode=stub` 핀 고려(메모 [[env-leak-into-tests]]).
- 🟢 불편 관측 시 (A) 표시-only 로 다운그레이드 옵션.

## 관련 메모리

- [[propose-slots-hold-guarantee]] — 이 결정의 핵심·파라미터·근거
- [[enrollment-domain-concept]] — 회차·session·좌석 lock 모델
- [[payment-followups-and-occupancy-rethink]] — 점유 시점 재검토 맥락
- [[avoid-stacked-prs]] — flyway 머지 후 작업해야 하는 이유
