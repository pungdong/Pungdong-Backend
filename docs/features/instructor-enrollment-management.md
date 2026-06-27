# 강사 수강관리 (instructor-enrollment-management)

> **피처 문서** — 정책·왜·히스토리를 소유. 구현(ER·엔드포인트·교집합)은 [docs/architecture/enrollment.md](../architecture/enrollment.md) 로 링크만. 학생 거울 = [student-schedule.md](student-schedule.md).

## 한 줄

강사가 **받은 신청 검토 · 일정변경 검토 · 세션 마무리**를 한 화면에서 처리하는 hub. 디자인 핸드오프 `features/enrollment-management` (강사 측 수강 관리, 모바일 11 + PC 9). **거래 단위 = 수강(수강생 × 강의)** — 학생 강의일정 hub 의 강사 거울.

## 협력 도메인

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| enrollment | [enrollment.md](../architecture/enrollment.md) | 수강/회차 본체 · 강사 시점 상태 파생 · accept/reject/propose/complete (이 피처) |
| availability | [availability.md](../architecture/availability.md) | 검토 시트의 24h day 캘린더(겹침·합류 확인) — FE 가 availability 캘린더 재사용 |
| course · venue | — | 강의/위치 메타(제목·단체·레벨·venue 이름) |

## 정책 (requirements)

### 거래 카드 = 수강 1건, 강사 시점 상태는 회차에서 파생 (저장 X)

학생 hub 와 동일한 원칙 — 상태를 저장하지 않고 회차(`EnrollmentRound`)들에서 매번 파생한다. 강사 관점 어휘:

- **회차(`InstructorRoundStatus`)**: `WAITING`(신규 신청) · `CHANGING`(학생 직접 일정수정 받음) · `PROPOSED`(강사가 일정변경요청함 → 학생 대기) · `PAYMENT_DUE`(수락됨·결제 대기) · `CONFIRMED`(확정·진행 예정) · `CLOSING`(세션 종료·마무리 필요) · `DONE` · `REJECTED` · `CANCELLED`.
- **카드(`InstructorEnrollmentStatus`)**: `ACTION_NEEDED`(WAITING/CHANGING/CLOSING 있음) · `PROGRESS` · `COMPLETED` · `CANCELLED`. 정렬 = 이 순서(액션 먼저).
- **플래그(`InstructorActionFlag`)**: `NEW_REQUEST` · `CHANGE_REQUEST` · `CLOSING` — 그 수강에서 강사가 지금 할 1차 행동(없으면 null). `actionLine` = 한 줄 안내.

### 액션은 기존 엔드포인트 재사용

hub 는 **읽기 집계**다. 실제 행동은 이미 있는 회차 단위 엔드포인트:
- `NEW_REQUEST`(WAITING) → `accept` / `reject`(1회차만) / `propose-slots`.
- `CHANGE_REQUEST`(CHANGING) → 학생이 직접 바꾼 슬롯을 검토 → `accept`(승인) 또는 `propose-slots`(재제안). 회차 카드의 **`previousSlot`** 이 "바꾸기 전 슬롯"이라 변경 diff 를 보여준다(학생 `slotHistory` 마지막).
- `CLOSING` → `complete`(회차 done) 또는 세션 일괄 `sessions/{id}/complete`.

### 학생 이력 = 이 강사와 실제 수강(done 회차 보유) 횟수

카드의 `student.isNew`/`historyCount` 는 **이 강사와의** 과거 수강 중 done 회차가 하나라도 있는 수강 수에서 파생(현재 수강 제외). 강사가 단골/신규를 구분해 응대하도록. 실명은 미수집이라 표시는 `nickName`.

### 범위 밖 (미구현 · 디자인엔 있음)

- **회차 채팅**(3-상태 아이콘) — 채팅 백엔드 없음. 디자인상 "내 일정 탭"으로 이동.
- **다이브로그 · 강사 회차 메모** — 후속.
- 단체/1:1 구분 — Course 에 해당 속성 없음(정원으로 추론 보류).

## 결정 히스토리

| 시점 | 결정 | 왜 |
|---|---|---|
| 2026-06-28 | 강사 hub = 학생 hub 의 거울(거래=수강생×강의), 상태 저장 X 파생 | 디자인 enrollment-management. 학생 `ScheduleHub` 패턴 재사용 — 일관성 + drift 없음 |
| 2026-06-28 | hub 는 읽기 집계, 액션은 기존 accept/reject/propose/complete 재사용 | 행동 로직 중복 방지. hub 는 "무엇을 해야 하나"만 파생 |
| 2026-06-28 | `CHANGING` 회차에 `previousSlot`(slotHistory 마지막) 노출 | 학생 직접 일정수정[[booking]] 을 강사가 "전→후"로 검토 |

## 미해결 / 확장

- 🟡 회차 채팅(group chat) · 다이브로그 · 강사 회차 메모.
- 🟢 검토 시트 24h 캘린더의 BE 데이터 — 현재 availability 캘린더 재사용(겹침/합류는 FE 가 그림), 전용 집계는 후속.
- 🟢 단체/1:1 코스 구분.

## 관련 메모리

- [[enrollment_domain_concept]] · [[availability_domain_concept]]
