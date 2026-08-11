# 수강생 강의일정 hub (student-schedule)

> **피처 문서** — 정책·왜·히스토리를 소유. 구현(ER·엔드포인트)은 도메인 문서로 링크. 설계 단일출처는 claude.ai/design `019dcf51…` `features/schedule/`.

## 한 줄

학생이 **자기 수강신청(enrollment)들을 강의 단위로 묶어 진행 상태별로 관리**하는 hub — 신청→결제→확정→(진행→완료→리뷰/자격증)까지. 거래 단위 = **강의(course)**, 진행 단위 = **회차(round = enrollment 1건)**.

## 협력 도메인

| 도메인 | 문서 | hub 에서의 역할 | 현 상태 |
|---|---|---|---|
| enrollment | [architecture/enrollment.md](../architecture/enrollment.md) | 거래·회차 데이터 + 상태(5값) | ✅ 있음 (`GET /enrollments/mine`) |
| payment | [features/payment.md](payment.md) | 결제 대기/완료·금액 | ✅ 결제, ✅ 선결제 만료/거절 자동환불(2026-08-07) |
| availability | [architecture/availability.md](../architecture/availability.md) | 회차=session(위치·시간) | ✅ 있음 |
| course | [architecture/course.md](../architecture/course.md) | 강의 정체성(title·org·level·회차정의) | ✅ 있음 |
| review | [architecture/review.md](../architecture/review.md) | 완료 후 리뷰 | ⚠️ **레거시(Lecture/Reservation), enrollment 미연결** |
| certificate | (없음) | 완료 후 자격증 등록 | ❌ BE 없음 |

## ★ 설계 ↔ BE 상태 매핑 + 갭 (핵심)

> ⚠️ **2026-08-11 정정** — 아래 매핑표는 오래 stale 했다(단일 회차·reschedule 미구현·환불 없음 전제). 다회차(2026-06-28)·일정변경·완료(done)·환불이 전부 shipped 라, "❌ 없음" 표기는 대부분 틀렸다. 현재 기준으로 고쳐 적었다.

설계는 **강의 7상태 + 회차 9분기**를 그리고, **BE enrollment 는 다회차(`Enrollment ⊃ EnrollmentRound`) + 저장 5상태 + 파생 뷰**(`RoundScheduleStatus`/`CourseScheduleStatus`)로 대응한다. 매핑:

### 회차(enrollment) 상태
| BE `EnrollmentStatus` | 설계 회차 status | hub 노출 |
|---|---|---|
| `PENDING` | (선결제 1회차) `payment_due` (결제 필요) / (2회차) `waiting` | ✅ `isFirstMeeting` 분기 |
| `ACCEPT_PENDING` | `waiting` (결제완료·강사 확인 중) | ✅ 선결제 신규 |
| `CONFIRMED` | `confirmed` (확정) | ✅ |
| `REJECTED` | `rejected` (강사 거절·복구가능) + 사유 | ✅ |
| `CANCELLED` | `cancelled` (학생 취소) | ✅ |
| `CONFIRMED` + `doneAt` | `done`(수강완료) | ✅ 강사 `completeRound`/`completeSession` + 세션일+24h 자동 sweep → 파생 `RoundScheduleStatus.DONE` |
| `ACCEPT_PENDING` + `proposedSlots` | `changing`(일정조정중) | ✅ `propose-slots`/`pick-slot`/`reschedule` shipped → 파생 `RESCHEDULING` |
| (게이트) | `locked`(잠금=다회차) | ✅ `RoundGate.nextSchedulable` — 직전 정규회차 done 이어야 다음 회차가 열린다 |
| `CANCELLED`(만료 유래) | `cancelled` | ✅ 선결제 만료 구현(미결제 12h·결제완료 24h+환불) — 별도 expired status 없이 CANCELLED |

### 강의(course) 상태 — 회차들에서 파생
| 설계 강의 status | 파생 규칙(현 buildable) |
|---|---|
| `payment_due` | 회차 중 결제 필요(미결제 PENDING) 있음 |
| `waiting` | 회차 중 강사 확인 대기(ACCEPT_PENDING · 2회차 PENDING) 있음 |
| `progress` | 회차 중 CONFIRMED 있음(그 외 액션 없음) |
| `rescheduling` | 회차 중 REJECTED 있음(복구 가능) |
| `cancelled` | 전부 CANCELLED |
| `finalizing` | 🟡 done 은 있으나 "마무리" 별도 단계는 없음(완료 즉시 COMPLETED) |
| `completed` | ✅ `CourseScheduleStatus.COMPLETED`(모든 정규회차 done). 자격증 발급은 여전히 없음 |

→ **7상태 중 6개 파생 가능.** 남은 건 `finalizing`(완료 직전 단계를 따로 둘지) 뿐이고, 자격증 발급만 여전히 도메인 부재.

### 회차 대여 장비 (2026-07-06)
회차 카드(`ScheduleRound`)에 내가 신청한 **대여 장비 내역 `gearItems`**(`{name, sizeLabel}`, 신청 시점 스냅샷)를 echo — 학생이 자기 일정에서 뭘 빌렸는지(핀 270 · 슈트 L) 본다. 강사 hub·강사 캘린더 신청자행과 **같은 공유 `GearItem`** 형태(단위는 FE 표기). 사이즈 캡처는 신청 요청 `equipmentSizes` 로(booking 참조).

> **회차 payload 에 결제 카운트다운이 있다** — `ScheduleRound.paymentExpiresInSeconds`(미결제 회차만). "OO분 안에 결제" 안내의 단일 출처이고, TTL 은 Sanity 운영값이라 클라이언트가 하드코딩하면 안 된다. `0` 은 "곧 만료" 로 다룬다(만료 스윕이 주기 폴링이라 즉시 차단이 아니다).

### 미구현 서브시스템 (설계가 요구, BE 없음 — 코드 grep 확인)
- ~~**출석/완료(done)**~~ ✅ shipped — `EnrollmentRound.doneAt` + 강사 `completeRound`/`completeSession` + 세션일+24h 자동 sweep. `progress→completed` 파생 가능.
- **강사 메모(회차별)** — enrollment 메모 필드 없음 (`AvailabilityHold.memo` 는 강사 외부예약 기록용, 무관).
- **세션 채팅(회차별 단체채팅)** — 엔티티/컨트롤러 없음 (enrollment "아직 안 한 것"에 명시).
- ~~**일정 변경(reschedule) 요청**~~ ✅ shipped — `reschedule`·`propose-slots`·`pick-slot`. 더 비싼 슬롯은 차액 결제(`-1018`), 위치까지 바뀌면 `-1019`, 제안 만료는 `-1020`.
- ~~**환불(refund)**~~ ✅ shipped — `RefundService`·`RefundCalculator`·`RefundOrder`(V15 원장)·`POST /enrollments/{id}/refund`. 거절·취소·무응답 만료는 자동 전액환불.
- ~~**결제 만료**~~ ✅ 선결제 전환(2026-08-07)으로 구현 — 미결제 PENDING 12h·결제완료 ACCEPT_PENDING 24h(+자동환불) 자동 만료(전 회차 동일)(`EnrollmentExpiryService`). CANCELLED 로 통합(별도 status 없음).
- **리뷰 ↔ 완료 enrollment 연결** — Review 는 레거시 `Lecture/Reservation` 에 묶임, `Course/Enrollment` 미연결.
- **자격증 등록** — certificate 도메인 BE 부재.
- ~~**다회차 진행(2회차+)**~~ ✅ shipped(2026-06-28) — `POST /enrollments/{id}/rounds` + `RoundGate` 순차 게이트.

## 구현 (Phase 1 — 이 PR)

**`GET /enrollments/mine/schedule`** (학생 인증) — `GET /enrollments/mine` 의 평탄 목록을 **강의별로 그룹핑 + 설계 상태어휘로 파생**한 hub read. 구현은 [architecture/enrollment.md](../architecture/enrollment.md) (※ 응답 모델·파생 규칙).

- 그룹: enrollment 들을 `courseId` 로 묶어 `ScheduleCourse`(강의 카드) → 그 안에 `ScheduleRound`(회차, roundIndex 순).
- 파생: 회차 status(=enrollment status 매핑), 강의 status(=회차들에서 위 규칙으로 파생), 필터 카운트.
- 데이터: enrollment 스냅샷(date·block·venue·instructor·가격·사유) 그대로. **추가 조회/조인 없음**(payment·memo 등은 Phase 2+).
- 정렬: 액션 우선(payment_due → waiting → rescheduling → progress → cancelled).

**왜 BE 가 그룹핑/파생하나** — 강의 상태 7→파생 규칙은 비즈니스 로직(단일 출처). FE 가 /mine 을 직접 그룹핑하면 규칙이 FE 로 샌다. 후속 enrichment(결제/메모/채팅)도 서버사이드로 붙는다.

## 로드맵 (Phase 2+ — QA 후 우선순위)

- 🟢 ~~출석/완료(done)~~ ✅ shipped — `doneAt` + 자동 sweep. 남은 건 **리뷰·자격증 사이클** 연결.
- 🟢 ~~결제 만료·환불 상태기계~~ ✅ shipped. 남은 건 PG **webhook**(비동기 취소 통보) — [payment.md](payment.md).
- 🟢 ~~일정 변경(reschedule)~~ ✅ shipped(강사측 hub 와 함께).
- 🟡 **세션 채팅** — 회차별 단체채팅(done=read-only).
- 🟢 **강사 메모(회차별)** · **리뷰 enrollment 연결**(레거시 Review→Course 이관) · **자격증 등록** 도메인.
- 🟢 **다회차 진행** — roundIndex 2+ 신청.

## 관련 메모리

- [[enrollment_domain_concept]] — session-bound·5상태·첫만남만·결제후확정.
- [[payment_followups_and_occupancy_rethink]] — 만료/환불 상태기계 후속, 점유 재검토.
- [[availability_domain_concept]] — coverage/session 2층.
