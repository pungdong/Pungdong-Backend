# 수강생 강의일정 hub (student-schedule)

> **피처 문서** — 정책·왜·히스토리를 소유. 구현(ER·엔드포인트)은 도메인 문서로 링크. 설계 단일출처는 claude.ai/design `019dcf51…` `features/schedule/`.

## 한 줄

학생이 **자기 수강신청(enrollment)들을 강의 단위로 묶어 진행 상태별로 관리**하는 hub — 신청→결제→확정→(진행→완료→리뷰/자격증)까지. 거래 단위 = **강의(course)**, 진행 단위 = **회차(round = enrollment 1건)**.

## 협력 도메인

| 도메인 | 문서 | hub 에서의 역할 | 현 상태 |
|---|---|---|---|
| enrollment | [architecture/enrollment.md](../architecture/enrollment.md) | 거래·회차 데이터 + 상태(5값) | ✅ 있음 (`GET /enrollments/mine`) |
| payment | [features/payment.md](payment.md) | 결제 대기/완료·금액 | ✅ 결제, ❌ 만료/환불 상태기계 |
| availability | [architecture/availability.md](../architecture/availability.md) | 회차=session(위치·시간) | ✅ 있음 |
| course | [architecture/course.md](../architecture/course.md) | 강의 정체성(title·org·level·회차정의) | ✅ 있음 |
| review | [architecture/review.md](../architecture/review.md) | 완료 후 리뷰 | ⚠️ **레거시(Lecture/Reservation), enrollment 미연결** |
| certificate | (없음) | 완료 후 자격증 등록 | ❌ BE 없음 |

## ★ 설계 ↔ BE 상태 매핑 + 갭 (핵심)

설계는 **강의 7상태 + 회차 9분기**를 그리지만, **BE enrollment 는 단일 회차(첫 만남)·5상태**다. 매핑:

### 회차(enrollment) 상태
| BE `EnrollmentStatus` | 설계 회차 status | hub 노출 |
|---|---|---|
| `PENDING` | `waiting` (강사 확인 중) | ✅ |
| `PAYMENT_PENDING` | `payment_due` (결제 필요) + amount | ✅ |
| `CONFIRMED` | `confirmed` (확정) | ✅ |
| `REJECTED` | `rejected` (강사 거절·복구가능) + 사유 | ✅ |
| `CANCELLED` | `cancelled` (학생 취소) | ✅ |
| — 없음 — | `done`(수강완료) | ❌ **출석/완료 개념 BE 없음** |
| — 없음 — | `changing`(일정조정중) | ❌ reschedule 미구현 |
| — 없음 — | `locked`(잠금=다회차) | ❌ 다회차 진행 미구현 |
| — 없음 — | `payment_expired`(결제만료) | ❌ 만료 상태기계 미구현 |

### 강의(course) 상태 — 회차들에서 파생
| 설계 강의 status | 파생 규칙(현 buildable) |
|---|---|
| `payment_due` | 회차 중 PAYMENT_PENDING 있음 |
| `waiting` | 회차 중 PENDING 있음 |
| `progress` | 회차 중 CONFIRMED 있음(그 외 액션 없음) |
| `rescheduling` | 회차 중 REJECTED 있음(복구 가능) |
| `cancelled` | 전부 CANCELLED |
| `finalizing` | ❌ **전 회차 done 개념 없음** |
| `completed` | ❌ **완료/자격증 발급 개념 없음** |

→ **현재 7상태 중 5개만 파생 가능.** `finalizing`/`completed` 는 출석·완료 추적이 BE 에 생겨야 가능.

### 회차 대여 장비 (2026-07-06)
회차 카드(`ScheduleRound`)에 내가 신청한 **대여 장비 내역 `gearItems`**(`{name, sizeLabel}`, 신청 시점 스냅샷)를 echo — 학생이 자기 일정에서 뭘 빌렸는지(핀 270 · 슈트 L) 본다. 강사 hub·강사 캘린더 신청자행과 **같은 공유 `GearItem`** 형태(단위는 FE 표기). 사이즈 캡처는 신청 요청 `equipmentSizes` 로(booking 참조).

### 미구현 서브시스템 (설계가 요구, BE 없음 — 코드 grep 확인)
- **출석/완료(done)** — enrollment 에 "수강 완료" 상태/시점 없음. CONFIRMED 가 끝. → `progress→finalizing→completed` 불가.
- **강사 메모(회차별)** — enrollment 메모 필드 없음 (`AvailabilityHold.memo` 는 강사 외부예약 기록용, 무관).
- **세션 채팅(회차별 단체채팅)** — 엔티티/컨트롤러 없음 (enrollment "아직 안 한 것"에 명시).
- **일정 변경(reschedule) 요청** — changing/rejected-대안날짜 없음.
- **환불(refund)** — `PaymentStatus.CANCELED` 값만, 로직 없음.
- **결제 만료(payment_expired)** — PAYMENT_PENDING 24h 자동취소 없음.
- **리뷰 ↔ 완료 enrollment 연결** — Review 는 레거시 `Lecture/Reservation` 에 묶임, `Course/Enrollment` 미연결.
- **자격증 등록** — certificate 도메인 BE 부재.
- **다회차 진행(2회차+)** — enrollment 는 첫 만남(roundIndex=1) 1건. "나머지 회차 일정 결정은 후속".

## 구현 (Phase 1 — 이 PR)

**`GET /enrollments/mine/schedule`** (학생 인증) — `GET /enrollments/mine` 의 평탄 목록을 **강의별로 그룹핑 + 설계 상태어휘로 파생**한 hub read. 구현은 [architecture/enrollment.md](../architecture/enrollment.md) (※ 응답 모델·파생 규칙).

- 그룹: enrollment 들을 `courseId` 로 묶어 `ScheduleCourse`(강의 카드) → 그 안에 `ScheduleRound`(회차, roundIndex 순).
- 파생: 회차 status(=enrollment status 매핑), 강의 status(=회차들에서 위 규칙으로 파생), 필터 카운트.
- 데이터: enrollment 스냅샷(date·block·venue·instructor·가격·사유) 그대로. **추가 조회/조인 없음**(payment·memo 등은 Phase 2+).
- 정렬: 액션 우선(payment_due → waiting → rescheduling → progress → cancelled).

**왜 BE 가 그룹핑/파생하나** — 강의 상태 7→파생 규칙은 비즈니스 로직(단일 출처). FE 가 /mine 을 직접 그룹핑하면 규칙이 FE 로 샌다. 후속 enrichment(결제/메모/채팅)도 서버사이드로 붙는다.

## 로드맵 (Phase 2+ — QA 후 우선순위)

- 🔴 **출석/완료(done) + finalizing/completed** — enrollment 에 완료 추적. hub 의 절반(완료·리뷰·자격증 사이클)이 여기 의존.
- 🟡 **결제 만료·환불 상태기계** — payment_expired·남은회차 환불. (payment 후속과 동일.)
- 🟡 **일정 변경(reschedule)** — changing/rejected-대안. enrollment-management(강사측)와 한 쌍.
- 🟡 **세션 채팅** — 회차별 단체채팅(done=read-only).
- 🟢 **강사 메모(회차별)** · **리뷰 enrollment 연결**(레거시 Review→Course 이관) · **자격증 등록** 도메인.
- 🟢 **다회차 진행** — roundIndex 2+ 신청.

## 관련 메모리

- [[enrollment_domain_concept]] — session-bound·5상태·첫만남만·결제후확정.
- [[payment_followups_and_occupancy_rethink]] — 만료/환불 상태기계 후속, 점유 재검토.
- [[availability_domain_concept]] — coverage/session 2층.
