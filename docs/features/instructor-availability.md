# 강사 가용시간 캘린더 (instructor-availability)

> **피처 문서** — 정책·왜·히스토리를 소유한다. 구현(ER·엔드포인트·권한)은 [docs/architecture/availability.md](../architecture/availability.md) 로 링크만.

## 한 줄

강사가 매일 여는 메인 도구 — 가용시간을 열고, 들어온 신청을 보고, 네이버·당근 같은 **외부 플랫폼 예약까지 풍덩 한곳에서** 관리해 "시간 관리는 풍덩에서"라는 습관을 만든다. (V2 디자인 `features/instructor-availability`.)

## 협력 도메인

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| availability | [availability.md](../architecture/availability.md) | 예약가능시간(coverage) + 일정(session: 위치·정원·외부hold) (이 피처의 본체) |
| account | — | `Account`(instructor) 단방향 참조 |
| instructor-application | [instructor-application.md](../architecture/instructor-application.md) | 진입 게이트(강사신청 보유) |
| venue | [venue.md](../architecture/venue.md) | `venueRefId` 검증/표시명 해석(`VenueRefValidator`/`VenueRefResolver`) |
| enrollment/booking | [enrollment.md](../architecture/enrollment.md) | 풍덩 수강생 점유(`pending`/`confirmed`/`applicants[]`) — session 에 바인딩, 연동됨 |

## 정책 (requirements)

### 2층 모델 — 예약가능시간(coverage) vs 일정(session)

옛 단일 `AvailabilityWindow` 가 *시간·위치·정원·점유*를 한 칸에 뭉쳤던 걸 2026-06-18 에 두 레이어로 쪼갰다.

- **예약가능시간(coverage)** = *예약을 받을 수 있는 순수한 시간 띠*("이 범위면 신청 가능" predicate). 위치·정원·사람 없음. 한 날의 coverage 는 항상 **자동 머지**돼 비겹침·비인접 구간[]으로 저장된다(10–12 + 12–14 → 10–14). 학생은 **coverage 안에서만** 신청할 수 있다.
- **일정(session)** = *위치·정원·사람을 가진 한 점유 블록*. coverage 위에 놓인 한 (위치, 시간블록). 강사가 외부예약/수동으로 추가하거나 학생 첫 신청이 만든다. 같은 (위치,블록)이면 새 session 이 아니라 **누적**(점유 join).
- **두 레이어의 결합 = 시간뿐**. coverage 구간은 머지/분할로 늘 바뀌므로 session 이 그걸 가리키지 않는다 — "session 시간이 coverage 안에 있나"로만 묶인다. 그래서 빈 일정(점유 0)은 그냥 AVAILABLE 로 남고, coverage 를 닫아도 session 이 사라지지 않는다(반대로 session 걸친 coverage 닫기는 거부, 아래).
- **점유(occupancy)** = *실제 점유*. 출처가 풍덩 수강생이든 외부 플랫폼이든 **동일하게 동작**하도록 단일 정의. session 에 붙는다.
- 디자인 원칙: "**사실은 보여주고, 판단은 강사가**" — 자동 경고·임의 임계값 없음. 겹치는 session, 정원 초과를 막지 않고 보여주기만 한다.

### coverage 편집 vs 일정 추가 — 두 진입점

- **예약가능시간 직접 편집**: 열기(`POST /coverage`, union·머지, recurrence ONCE/WEEKLY/FOUR_WEEKS 로 여러 날 한 번에)·닫기(`DELETE /coverage`, subtract, 단일 날). "예약 받을 시간"만 손대는 가벼운 도구.
- **일정 추가(원자)**: `POST /sessions` 한 번이 ① 그 시간대를 덮도록 **coverage 를 자동으로 들어올리고(union+머지)** ② 그 (위치,시간) session 을 찾거나 만들고 ③ 점유(외부예약/±)를 기록한다 — 한 트랜잭션. 옛 "window 생성 + hold 추가" 2-call 폐기. 즉 강사가 외부예약을 적으면 예약가능시간도 같이 열린다(외부예약이 곧 "그 시간 일한다"는 선언).
- **coverage 닫기는 session 을 가로지르면 거부**(`COVERAGE_HAS_SESSION` / `-1014`). BE 가 일정을 임의로 지우면 CS 가 나므로, 식별 가능한 코드를 내려 FE 가 "내부 일정을 먼저 정리해주세요"로 유도한다. (맞닿음은 충돌 아님 — strict 겹침만.)
- 정리: **학생은 coverage 안에서만 예약**하고, **강사의 일정 추가는 coverage 를 넓힌다**(좁히지 않는다 — 좁히는 건 닫기 전용).

### 5가지 슬롯 상태 (파생)

`AVAILABLE · PENDING · CONFIRMED · EXTERNAL · FULL` — **저장값이 아니라 점유에서 파생**한다. 일상어 카피("확정/대기/외부 포함") 사용, "잠긴 슬롯" 같은 시스템 용어 회피.

### 두 가지 점유 조정 = 단일 테이블

- **± 빠른조정** — 메모 없이 정원 1칸 점유(`memo=null`, `count=1`). 일상 케이스.
- **외부예약** — 메모 + 인원 1~N(단체 커버). 점유가 정원 넘으면 그 일정이 **커스텀 정원(=점유)으로 확장**(6명 넣으면 6/6).
- 둘 다 같은 `AvailabilityHold` row — `memo` 유무로만 구분. (모델을 단단하게.)

### 정원 — 계정 기본값(baseline) + 일정 override (2026-06-16)

정원은 "한 강사가 동시에 커버 가능한 인원" = **강사 계정 속성**(`Account.defaultCapacity`, 기본 4)이지 날짜 속성이 아니다. (2026-06-18 분리에서 `capacityOverride` 가 window→**session**으로 옮겨졌다 — 모델은 동일.) 그래서:

- 일정(session)은 **override 가 없으면 계정 기본값을 라이브로 참조**(`effectiveCapacity = capacityOverride ?? account.defaultCapacity`). 스냅샷이 아니라 참조라 — 안 건드린 일정엔 숫자를 저장하지 않으므로 **기본값을 바꾸면 그 일정들이 즉시 따라간다(전파 write 불필요)**.
- 그 날만 다르게 가려면 **일정 카드 ±로 override 고정**(예: 고레벨이라 2명). override 한 일정은 baseline 변경에 영향받지 않는다(명시적 예외).
- 계정 baseline ±(일정탭)과 일정 override ±(카드)는 **서로 독립** — 어느 쪽을 눌러도 다른 쪽/이미 확정된 일정은 의도대로 안전.
- **점유 추가 vs 정원 낮춤 = 비대칭** (2026-06-18 보정): 강사가 **점유를 추가**(외부예약/±)해 정원을 넘기면 그 일정이 **커스텀 정원(=점유)으로 확장**("그만큼 받겠다"는 선언). 반대로 강사가 **정원을 점유보다 낮추면** 확장 안 하고 **바닥 유지**(확정 enrollment·외부 hold 는 취소 없이 유지, over 표시·새 수락만 차단). → `X/Y where X>Y` 는 **낮췄을 때만** 나온다. 학생 신청은 만석 캡이라 확장 트리거 안 됨.
- FE 는 `capacityOverridden` 으로 "직접 설정" 배지·"기본값 따르기"(override 해제) 노출을 판단. 강사가 override 한 걸 잊는 케이스는 이 배지로 보완.
- **왜 스냅샷이 아니라 라이브 참조인가**: 강사 멘탈모델이 "내가 4→6 늘렸으면 일정들도 6"(라이브)에 가깝고, 진짜 불변식은 "정원 동결"이 아니라 "**확정 예약은 취소당하지 않는다**" — 그건 확정 바닥이 보장. 결정 과정은 [[availability-domain-concept]].

엔드포인트(구현은 [docs/architecture/availability.md](../architecture/availability.md)): 계정 baseline `GET/PATCH /instructor/availability/settings`, 일정 override `PATCH`(설정)·`DELETE`(해제) `/instructor/availability/sessions/{id}/capacity`.

### 게이트 / 권한

- 진입 = **강사신청 보유**(상태 무관, SUBMITTED 포함). 리뷰 대기 중에도 가용시간 준비 허용. 가용시간은 종목별이 아니라 강사 단위라 종목 조건 없음. → [[instructor-review-window-allows-prep]].
- 없음/비소유 session = 400(존재 숨김, venue 패턴).

### 풍덩 수강생 점유 = enrollment 연동

풍덩 수강생 신청(`pending`/`confirmed`, 슬롯 안 `applicants[]` 이름·단체·레벨·장비)은 **enrollment/booking 도메인 산물**이고, 이제 연동됐다 — enrollment 가 session(`session_id`)에 바인딩되고 캘린더가 session 별로 집계해 `confirmedCount`/`pendingCount`/`applicants[]` + 5상태(`deriveStatus`)를 채운다. 학생 첫 신청이 (위치,블록) session 을 만들고, 같은 (위치,블록)은 그 session 에 join. → [docs/features/booking.md](booking.md).

## 결정 히스토리

| 시점 | 결정 | 근거 / 출처 |
|---|---|---|
| 2026-06 (V2 디자인) | 2층 모델(window/점유) | 출처 무관 동일 동작 — 디자인 브리프 |
| 2026-06 (V2 디자인) | ± · 외부예약 = 단일 hold 테이블(memo 구분) | 모델 단단하게 — 브리프 "두 가지 정원 조정" |
| 2026-06 (V2 디자인) | 외부 인원 dot = 차콜, 카드색은 확정과 통일 | 연한 확정 블루와 명도 대비 — 디자인 협의 §4 |
| 2026-06-15 (PR) | v1 = BE-소유 코어, applicants 빈 채로 | enrollment 도메인 부재 — 사용자 확인 |
| 2026-06-15 (PR) | 게이트 = 강사신청 보유(종목 무관), `existsByAccountId` | venue 기조 답습 — 사용자 확인 |
| 2026-06-16 (PR) | 정원 = `Account.defaultCapacity`(기본 4) 종속 + 일정 `capacityOverride`(sparse) 라이브 참조 | 정원은 강사 속성 · 매번 입력/전파 비효율 회피 — 사용자 결정 |
| 2026-06-16 (PR) | "정원 자동확장" 폐기 → 확정 바닥(유효정원<점유 허용, 확정 취소 없음·추가만 차단) | 진짜 불변식은 "확정 취소 없음"이지 "정원 동결" 아님 — 사용자 결정 |
| 2026-06-18 (PR) | 단일 `AvailabilityWindow` → **coverage(예약가능시간) + session(일정) 2층 분리** | window 가 시간·위치·정원·점유를 뭉쳐 의미 충돌 → coverage=순수 시간 predicate(자동 머지) / session=위치·정원·사람. 결합은 시간 포함뿐(FK 없음). 일정 추가 = coverage 들어올림+session 1트랜잭션(2-call 폐기). 닫기 vs session 충돌 = -1014(BE 자동정리 안 함). `capacityOverride` 는 session 으로 이전. — 사용자 결정 |
| 2026-06-18 (PR) | 빈 일정(점유 0) 자동 삭제(이력 보존) · 일정 시간겹침 금지(-1015) · 점유 추가가 정원 넘기면 커스텀 정원 확장 | session 존재⟺점유>0 / 한 강사=한 번에 한 세션(겹침=이중부킹·정원 이중계산) / "정원 자동확장 폐기"(2026-06-16)는 *낮춤* 케이스만 — *추가* 는 6명 넣으면 6/6 으로 확장(강사 선언). 셋 다 사용자 지적 |

## 미해결 / 확장

- 🟡 **coverage 닫기 = 수동 정리 의존** — session 가로지르면 -1014 거부, BE 자동 정리 안 함(CS 회피). FE 가드 + (필요 시) "일정 함께 정리" 확인 플로우.
- 🟢 **대기 신청 푸시 알림** — notification outbox 연동, enrollment 와 함께.
- 🟢 **OFFICIAL venueRef 이름 해석** — Sanity 서버사이드 읽기/캐시 완성 의존 → [[venue-sanity-sync-design]].
- 🟢 **새 baseline 일괄 적용 버튼** — 라이브 참조라 override 없는 session 은 자동 반영, override 한 session 까지 되돌리는 액션은 후속.

## 관련 메모리

- [[venue-domain-concept]] — 강사 availability ∩ Venue 시간대 = 수강생 선택지(이 피처의 student-facing 후속)
- [[instructor-review-window-allows-prep]] — 리뷰 대기 중 준비 허용(게이트 기조)
- [[venue-sanity-sync-design]] — OFFICIAL venue 동기화(venueName 해석 의존)
