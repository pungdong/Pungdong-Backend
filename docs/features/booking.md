# 수강신청 (booking / enrollment)

> **피처 문서** — 정책·왜·히스토리를 소유. 구현(ER·엔드포인트·교집합 알고리즘)은 [docs/architecture/enrollment.md](../architecture/enrollment.md) 로 링크만.

## 한 줄

학생이 강의 상세 "신청하기" → 일정·위치·시간·장비를 고르고 → 강사 답변을 기다리는 흐름. V2 디자인 `features/booking`(모바일 6 + 데스크탑 4 화면). **강사가 만든 데이터(availability·venue·course)가 그대로 학생 선택지로 연결되는 지점** — 풍덩의 도메인들이 처음 한 화면에서 만난다.

## 협력 도메인

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| enrollment | [enrollment.md](../architecture/enrollment.md) | 신청 본체 · 교집합 · 수락/거절 (이 피처) |
| availability | [availability.md](../architecture/availability.md) | 예약가능시간(coverage, 자격 판정) · 슬롯 capacity 단위(session) · 점유 반영 |
| venue | [venue.md](../architecture/venue.md) | 운영 시간블록·입장료(daypart fee)·장비 |
| course | [course.md](../architecture/course.md) | 1회차 후보 위치·이용권·수강료 |
| account · instructor-application | — | 학생/강사 · 강사 게이트 |
| payment | [payment.md](payment.md) · [architecture](../architecture/payment.md) | 강사 수락 후 결제(토스 결제위젯) → 확정. 정산 후속 |

## 정책 (requirements)

### 교집합 — 무엇이 학생 선택지가 되나

**선택지 = `강사 coverage(예약가능시간) ∩ venue 운영 시간블록 ∩ 코스 1회차 후보 위치`.** 이게 venue·availability 두 도메인이 존재한 궁극적 이유(메모 [[venue-domain-concept]]·[[availability-domain-concept]]). 핵심: **venue 운영블록이 강사 coverage 에 통째로 ⊆ 일 때만** 슬롯이 된다(`CoverageMerger.containsWhole` — 부분겹침 불가). BE 가 교집합을 **평탄 슬롯 집합**으로 계산해 내려주고, **UX 순서(날짜→위치→시간)와 계산 순서는 분리** — FE 가 평탄 집합을 날짜로 그룹핑.

### 예약 단위(session) + exact-match join

- 슬롯의 capacity 단위 = 강사 `AvailabilitySession`(위치·시간블록·정원). **session 은 강사가 직접 추가하거나, 학생 첫 신청이 그 (위치,블록)으로 생성**한다 — coverage(예약가능시간) 위에 놓인 한 일정. (2026-06-18 분리에서 옛 `AvailabilityWindow` 가 coverage+session 둘로 쪼개졌고, 예약은 session 에 붙는다. → [docs/features/instructor-availability.md](instructor-availability.md).)
- 이후 신청은 **같은 venue + 정확히 같은 시간블록**(같은 session)만 합류(group session). **부분겹침은 불허** — venue 운영블록은 이산 카탈로그(1부/2부…)라 통째로만 선택하므로 부분겹침이 구조적으로 표현 불가. (사용자 결정.) 슬롯 식별자 = `(date, 위치, 블록)`(옛 windowId 폐기).
- **session 은 별도 bind/unbind 없음**(옛 `WindowBinder` 제거) — 생성 시점부터 위치를 소유. 대신 **점유 0 = 일정 삭제**: 거절/취소(또는 외부 hold 제거)로 활성 신청+hold 가 0 이 되면 `SessionCleaner` 가 그 session 을 지운다(화면 카드 제거). **단 enrollment 이력은 보존** — CANCELLED/REJECTED 신청은 안 지우고 `session_id` 만 끊는다(날짜·위치·블록·가격·사유 스냅샷은 enrollment 에 남아 CS/환불 증빙 가능). coverage 는 안 건드림.
- **만석** = `확정 + 외부 hold >= 유효정원`. PENDING 은 정원 넘게 쌓여도 허용 — 강사가 수락/거절로 정리(디자인 PendingBanner). 수락 시 정원 재검증.

### 첫 만남만 + 결제 시점

- **1회차(첫 만남)만 신청.** 나머지 회차는 수강하면서 결정(디자인 "첫 만남 날짜만 정해주세요").
- **신청 시 결제 없음.** 강사 확정 후 결제 링크가 푸시로 도착 → 결제 완료 시 예약 확정(디자인 ⑥ "다음 단계" 3-step). v1 은 결제 단계 미구현이라 **강사 수락=CONFIRMED**.
- 총액 = 수강료 + 위치 입장료 + 위치별 장비. 신청 시점은 **추정치(스냅샷)**, 권위 금액은 확정/결제 시 서버 재계산.

### 상태 / 권한

- `PENDING`(답변 대기) → `CONFIRMED`(수락) / `REJECTED`(거절·복구 가능) / `CANCELLED`(학생 취소, 대기 중).
- 거절/취소로 session 의 활성 신청이 0 이 되면 그냥 AVAILABLE 로 남는다(session 잔존, bind/unbind 없음).
- 학생 신청은 인증만(누구나 OPEN 코스). 강사 측은 강사신청 보유 게이트. 없음/비소유 = 400(존재 숨김).

### 슬롯 신청자 행의 단체·레벨 = 평탄 3종 + FE 가 Sanity 로 표시명 해석 (2026-06-17)

강사 캘린더의 슬롯 신청자 행(`AvailabilitySessionResponse.applicants[]`, availability 도메인이 enrollment 를 집계해 렌더)은 단체·레벨을 **평탄 3종**으로만 내린다: `organizationCode` · `disciplineCode` · `levels[]`(코스 목표 레벨). 이게 **BE↔Sanity 공유 키**(평탄화 `CertLevel` 계약).

- **표시명은 FE 가 Sanity cert 카탈로그로 해석**한다 — `(org, discipline, level)` 로 `certificationsByOrgAndDiscipline` 에서 그 단체 `displayName` 룩업(예: `PADI·SCUBA·LEVEL_1` → "Open Water Diver"). BE 는 단체별 명칭을 모르고 캐싱도 안 한다.
- **왜 BE 캐시 아닌가**: cert org 카탈로그는 **FE-direct CDN** 결정(venue 와 달리 BE 가 권위검증·머지 안 함). 표시 라벨 하나 때문에 BE cert 캐시(reconcile/webhook)를 세우는 건 "CDN 재발명" 안티패턴. → [[sanity-read-principle]], `sanity/CLAUDE.md` "읽기 기조".
- **레벨 = 코스 목표 레벨**(학생 본인 자격 아님 — enrollment 에 학생 cert 미수집). 범위 코스(`levels` 여러 개)면 표시 규칙은 FE.

## 결정 히스토리

| 시점 | 결정 | 근거 |
|---|---|---|
| 2026-06 (V2 디자인) | 일정→위치→시간→장비 진행형, 신청 시 결제 없음 | 디자인 booking 흐름 |
| 2026-06 (V2 디자인) | 첫 만남만 신청, 나머지는 수강 중 | 디자인 "첫 만남 날짜만" |
| 2026-06-16 (PR) | window-bound 모델 → exact-match join(부분겹침 불가) | 사용자 결정("같은 venue·정확히 같은 시간대만 합류") |
| 2026-06-16 (PR) | 교집합 = 평탄 슬롯, UX/계산 순서 분리 | 사용자 토의(날짜-first UX vs venue-first 계산) |
| 2026-06-16 (PR) | v1 = 신청+강사응답+캘린더 연동, 결제/정산 후속 | 사용자 scope 확정 |
| 2026-06-18 (PR) | window → coverage/session 분리에 맞춰 **session-bound** 로 retarget | availability 2층 분리 — 예약은 session 에 붙고, 자격은 블록 ⊆ coverage. 슬롯 식별자 = (date,위치,블록), `availabilityWindowId` 폐기, `WindowBinder` 제거(session 이 위치 소유). → instructor-availability 2026-06-18 행 |

## 미해결 / 확장

- 🟡 **결제(PG)·정산** — 수락→결제링크 푸시→결제완료=확정. 수수료(PG 3.4% + 플랫폼 6.6%, 실비 0%) 분해.
- 🟢 **장비 사이즈 캡처**(핀 mm·슈트 S~XL, 사이즈 선택 전 결제 차단) · 세션 단체채팅/공지.
- 🟢 **다회차 진행 중 일정 결정**(2회차+) · 환불/재일정 상태기계 · enrollment-management 강사 검토 시트 풀 UI.
- 🟢 venue 운영 공휴일·OPEN 세분화 정밀도.

## 관련 메모리

- [[availability-domain-concept]] — coverage(예약가능시간)/session(일정) 2층 모델, 이 피처가 채우는 자리
- [[venue-domain-concept]] — availability ∩ Venue = 수강생 선택지(이 피처가 그 교집합)
- [[instructor-review-window-allows-prep]] — 강사 게이트 기조
