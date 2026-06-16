# 강사 가용시간 캘린더 (instructor-availability)

> **피처 문서** — 정책·왜·히스토리를 소유한다. 구현(ER·엔드포인트·권한)은 [docs/architecture/availability.md](../architecture/availability.md) 로 링크만.

## 한 줄

강사가 매일 여는 메인 도구 — 가용시간을 열고, 들어온 신청을 보고, 네이버·당근 같은 **외부 플랫폼 예약까지 풍덩 한곳에서** 관리해 "시간 관리는 풍덩에서"라는 습관을 만든다. (V2 디자인 `features/instructor-availability`.)

## 협력 도메인

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| availability | [availability.md](../architecture/availability.md) | 가용시간 window + 외부/수동 점유 hold (이 피처의 본체) |
| account | — | `Account`(instructor) 단방향 참조 |
| instructor-application | [instructor-application.md](../architecture/instructor-application.md) | 진입 게이트(강사신청 보유) |
| venue | [venue.md](../architecture/venue.md) | `venueRefId` 검증/표시명 해석(`VenueRefValidator`/`VenueRefResolver`) |
| (미래) enrollment/booking | — | 풍덩 수강생 점유(`pending`/`confirmed`/`applicants[]`) — v1 미연동 |

## 정책 (requirements)

### 2층 모델 — 가용시간 vs 점유

- **가용시간 window** = *이론적 가능성*. 강사가 "이 날 이 시간에 열려 있다"고 선언한 한 칸. 위치·세션은 선택(빈 가용시간은 시간만).
- **점유(occupancy)** = *실제 점유*. 출처가 풍덩 수강생이든 외부 플랫폼이든 **동일하게 동작**하도록 단일 정의.
- 디자인 원칙: "**사실은 보여주고, 판단은 강사가**" — 자동 경고·임의 임계값 없음. 겹치는 window, 정원 초과를 막지 않고 보여주기만 한다.

### 5가지 슬롯 상태 (파생)

`AVAILABLE · PENDING · CONFIRMED · EXTERNAL · FULL` — **저장값이 아니라 점유에서 파생**한다. 일상어 카피("확정/대기/외부 포함") 사용, "잠긴 슬롯" 같은 시스템 용어 회피.

### 두 가지 점유 조정 = 단일 테이블

- **± 빠른조정** — 메모 없이 정원 1칸 점유(`memo=null`, `count=1`). 일상 케이스.
- **외부예약** — 메모 + 인원 1~N(단체 커버). **유효정원을 넘겨도 기록**(FULL 로 표시, 자동확장 없음).
- 둘 다 같은 `AvailabilityHold` row — `memo` 유무로만 구분. (모델을 단단하게.)

### 정원 — 계정 기본값(baseline) + 일정 override (2026-06-16)

정원은 "한 강사가 동시에 커버 가능한 인원" = **강사 계정 속성**(`Account.defaultCapacity`, 기본 4)이지 날짜 속성이 아니다. 그래서:

- 일정(window)은 **override 가 없으면 계정 기본값을 라이브로 참조**(`effectiveCapacity = capacityOverride ?? account.defaultCapacity`). 스냅샷이 아니라 참조라 — 안 건드린 일정엔 숫자를 저장하지 않으므로 **기본값을 바꾸면 그 일정들이 즉시 따라간다(전파 write 불필요)**.
- 그 날만 다르게 가려면 **일정 카드 ±로 override 고정**(예: 고레벨이라 2명). override 한 일정은 baseline 변경에 영향받지 않는다(명시적 예외).
- 계정 baseline ±(일정탭)과 일정 override ±(카드)는 **서로 독립** — 어느 쪽을 눌러도 다른 쪽/이미 확정된 일정은 의도대로 안전.
- **유효정원을 점유보다 낮춰도 막지 않는다(확정 바닥)**: 이미 잡힌 점유(확정 enrollment·외부 hold)는 유지(취소 없음), 새 신청 수락만 차단. 옛 "정원 자동확장"은 이 바닥 개념으로 흡수(저장값을 안 올려 외부 hold 빠지면 자동 원복).
- FE 는 `capacityOverridden` 으로 "직접 설정" 배지·"기본값 따르기"(override 해제) 노출을 판단. 강사가 override 한 걸 잊는 케이스는 이 배지로 보완.
- **왜 스냅샷이 아니라 라이브 참조인가**: 강사 멘탈모델이 "내가 4→6 늘렸으면 일정들도 6"(라이브)에 가깝고, 진짜 불변식은 "정원 동결"이 아니라 "**확정 예약은 취소당하지 않는다**" — 그건 확정 바닥이 보장. 결정 과정은 [[availability-domain-concept]].

엔드포인트(구현은 [docs/architecture/availability.md](../architecture/availability.md)): 계정 baseline `GET/PATCH /instructor/availability/settings`, 일정 override `PATCH`(설정)·`DELETE`(해제) `/instructor/availability/{id}/capacity`.

### 게이트 / 권한

- 진입 = **강사신청 보유**(상태 무관, SUBMITTED 포함). 리뷰 대기 중에도 가용시간 준비 허용. 가용시간은 종목별이 아니라 강사 단위라 종목 조건 없음. → [[instructor-review-window-allows-prep]].
- 없음/비소유 window = 400(존재 숨김, venue 패턴).

### v1 경계 — applicants 빈 채로

풍덩 수강생 신청(`pending`/`confirmed`, 슬롯 안 `applicants[]` 이름·단체·레벨·장비)은 **enrollment/booking 도메인 산물**인데 BE 에 아직 그 도메인이 없다(lecture/reservation 재설계 대기). v1 은 응답 **모양만** forward-compatible 하게 잡고 빈 채로 둔다 — 5상태 모델은 완비, 캘린더는 `AVAILABLE` ↔ `EXTERNAL`/`FULL` 만 실제로 그려진다.

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

## 미해결 / 확장

- 🟡 **enrollment 연동** — 풍덩 수강생 `pending`/`confirmed` 점유 + `applicants[]` 실데이터. booking 도메인 생길 때 `deriveStatus` 의 confirmed/pending 만 채우면 동작.
- 🟡 **강사 availability ∩ Venue 운영시간 교차 = 수강생 선택지**(student-facing) — booking 도메인 소유. → [[venue-domain-concept]].
- 🟢 **대기 신청 푸시 알림** — notification outbox 연동, enrollment 와 함께.
- 🟢 **OFFICIAL venueRef 이름 해석** — Sanity 서버사이드 읽기/캐시 완성 의존 → [[venue-sanity-sync-design]].

## 관련 메모리

- [[venue-domain-concept]] — 강사 availability ∩ Venue 시간대 = 수강생 선택지(이 피처의 student-facing 후속)
- [[instructor-review-window-allows-prep]] — 리뷰 대기 중 준비 허용(게이트 기조)
- [[venue-sanity-sync-design]] — OFFICIAL venue 동기화(venueName 해석 의존)
