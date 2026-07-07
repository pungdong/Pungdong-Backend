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
- **만석(신청 시점 좌석 lock · 선착순)** = `활성(대기+결제대기+확정) + 외부 hold >= 유효정원`. **신청과 동시에 좌석을 잡는다**(영화관 좌석식 — 정원 차면 새 신청 400). 정원 ~5명 소규모라 먼저 신청한 사람이 밀리지 않게(2026-06-28 결정 — 옛 "PENDING 하드캡 안 함" 폐기). 수락은 잠긴 슬롯을 결제대기로 전환만(정원 재검증 없음).
- **lock 자동 만료(TTL)** — 방치된 점유 해제: 신청(PENDING) **24h** 무응답 / 결제대기(PAYMENT_PENDING) **12h** 미결제면 만료(CANCELLED·좌석 해제, `EnrollmentExpiryService` 주기 스위프). 값은 `SiteSettings`(Sanity) **런타임 config**(기본 24/12, Studio 에서 무배포 조정). 만료 전 학생 무료취소 가능. (만료 푸시 알림은 후속.)
- **선택 불가 슬롯 = 생략이 아니라 표기**(2026-07-01) — 옵션 응답에서 *생략(필터)* 과 *비활성 표기* 를 가른다. **기준: "막은 사유가 사라지면 예약 가능한 슬롯인가?"** — 그렇다면 표기, 아니면 생략.
  - **표기**(슬롯 내려가고 `unavailableReason` 세팅, FE 비활성): **만석**(`FULL`) · **강사가 같은 시간 다른 위치/블록에 이미 일정**(`TIME_CONFLICT`, 동시에 두 곳 불가 — FE 카피 "다른 일정이 있어요"). 강사가 *내놓은* 시간인데 지금만 막힌 것.
  - **생략**(슬롯 자체가 없음): coverage 밖(강사가 안 연 시간) · 휴무 · 미판매. 애초에 선택지가 아니라, 표기하면 매일 모든 운영블록이 회색으로 깔려 노이즈.
  - 모든 옵션 경로(1회차·다음회차·학생 일정수정·강사 제안)에 일관 적용. 구현은 [enrollment.md](../architecture/enrollment.md)(`EnrollmentOptionsService`).

### 다회차 모델 (붕어빵) — Course ⊃ rounds, Enrollment(수강) ⊃ EnrollmentRound(회차)

> **2026-06-28 재설계.** v1 "첫 만남(1회차)만" 은 의도적 축소였다. 자격과정은 보통 **주1회×N회**라 회차가 본(本) 모델 — 강사들의 회차 관리 난도가 이 hub 의 존재 이유.

- **Course = 붕어빵 틀**(수강료 + `CourseRound[]` 정의: 회차별 내용·위치·이용권). 신청하면 **Enrollment = 수강 컨테이너 1건(붕어빵)**, 각 **회차(EnrollmentRound)** 가 그 수강에 **sub 로 맵핑**. 예약·일정·결제·완료는 **회차 단위**, 수강은 회차 묶음 + 수강료 보유. 강의(수강) 상태는 회차들에서 **파생**(저장 X).
- **회차 진행(MVP) = 순차**: 직전 정규회차가 **done(완료)** 되면 다음 회차 일정 신청이 열린다(그 전 `locked`). 1회차는 수강 시작과 동시. BE 모델은 회차가 독립 row 라 **앞당김 예약을 네이티브 지원** — "예약 미리하기"는 게이트 정책만 완화(엔티티 불변). (구현: schedulable 정책 → [enrollment.md](../architecture/enrollment.md).)
- **추가세션(EXTRA)** = 정규 끝난 뒤 **현장 협의**(보통 자격 미통과)로 학생이 일반 회차처럼 신청. `roundKind=EXTRA` + 추가세션비.

### pay-first 결제 — 수락 → 결제 → 강사 수영장 예약

- 흐름: 신청 → **강사 수락(`PAYMENT_PENDING`)** → **학생 결제(`CONFIRMED`)** → 강사가 수영장 예약. 옛 "풀 먼저 예약 후 수락" 은 학생 미결제 시 강사가 **수영장 패널티**를 뒤집어쓰는 구멍이라 **pay-first 로 뒤집음**(돈 확보 후 풀 예약). 풀부킹 실패는 강사 일정변경요청 또는 **전액 무료 환불**(학생 무과실) — 학생에겐 "결제완료"로만 보이고 별도 상태 없음.
- **수강료는 1회차에 전액**(수강 커밋), **부대비용(입장료·장비)은 회차별**(위치·요일 따라 일정 잡을 때 확정). → 1회차 결제 = 수강료+부대, 2회차~정규 = 부대만, EXTRA = 부대+추가세션비.
- 금액: **수강료 = 수강 시점 스냅샷 고정**, 부대비용 = 회차 일정 시점 스냅샷. 권위(청구) 금액은 결제 시 서버 재계산. 장비는 회차별 명세(사이즈 포함, `sizeOptions` 멤버십 검증). (메커니즘 [payment.md](payment.md).)

### 액션 매트릭스 — 단계별 가능 행위

| 단계 | 강사 | 수강생 |
|---|---|---|
| **1회차 신청(수강 진입)** | 수락 / **거절** / 일정변경요청 | (대기) · 일정변경요청 시 → 일정변경(제안수락) / **수강취소** |
| **진행 중(2회차+)** | 수락 / 일정변경요청 *(거절 없음)* | 일정변경 / **환불신청** *(수강취소 없음)* |

- **거절·수강취소는 진입(1회차) 한정** — 진행 중엔 강사가 "거절"(못 가르치겠다) 불가, 일정변경요청만. 학생도 일부 수강했으니 "취소"가 아니라 환불신청. → `REJECTED` 는 1회차/진입 한정.

### 취소 / 환불 — 경계는 결제

- **결제 전**(`PENDING`·`PAYMENT_PENDING`) = 순수 취소(무료). pay-first 라 강사가 풀을 안 잡아 손해 0. (cancel 허용을 PAYMENT_PENDING 까지 확장.)
- **결제 후** = 취소 = **환불 거래**. 강사/venue 사유(풀부킹 실패 등) = 전액 무료, **학생 사유 = 환불정책 페널티**. done 회차 = 0, 미배정 회차 = 수강료÷N 전액.
- 환불 **구현 완료**(2026-06-28): `POST /enrollments/{enrollmentId}/refund`(수강 종료=남은 회차 환불). 회차별 산정(`RefundCalculator`) + 토스 부분취소(수강료=1회차 주문, 부대=각 회차 주문). 율·실행 메커니즘은 [payment.md](payment.md).

### 상태 / 권한

- 회차 상태(`EnrollmentStatus`, 5값): `PENDING`(답변 대기) → `PAYMENT_PENDING`(강사 수락·결제 대기) → `CONFIRMED`(결제·확정) / `REJECTED`(1회차 거절·복구 가능) / `CANCELLED`(결제 전 취소). **done = `CONFIRMED && doneAt!=null`**(별도 enum 없음 — 완료 시점 타임스탬프). 강의(수강) 상태는 회차들에서 파생.
- **취소 무료 = 결제 전**(PENDING·PAYMENT_PENDING). 거절/취소로 session 활성 신청이 0 이 되면 session 은 잔존(bind/unbind 없음), 이력 스냅샷은 보존.
- 학생 신청은 **로그인 + 본인인증(휴대폰 SMS) 선행**(2026-07-08) — 정책상 수강생은 수강신청 전 본인인증. 미인증이면 403 -1017(FE → 본인인증 화면), 2회차+ 는 전이적 통과(무만료). 강사 측은 강사신청 보유 게이트. 없음/비소유 = 400(존재 숨김). 게이트 상세는 [identity-verification 피처](identity-verification.md).

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
| 2026-06-28 (재설계) | **단일회차 → 다회차**: `Enrollment(수강) ⊃ EnrollmentRound(회차)` 분할. 슬롯·상태·부대비용이 회차로 내려가고 수강은 묶음+수강료 보유 | 자격과정은 주1회×N회가 본 모델, 강사 회차관리 난도가 hub 의 이유. Course 는 이미 `CourseRound[]` 보유. 회차=CourseRound FK |
| 2026-06-28 (재설계) | **pay-first**: 수락→결제→강사 수영장 예약 (옛 풀먼저예약→수락 폐기) | 미결제 시 강사 수영장 패널티 구멍 차단. 풀부킹 실패=무료환불/일정변경. 환불 경계가 "결제"로 깔끔해짐 |
| 2026-06-28 (재설계) | 수강료=1회차 전액·enrollment 스냅샷 / 부대비용=회차별 / 액션매트릭스(거절·취소는 1회차 한정) / 게이트=직전 done(앞당김은 정책완화) / 환불 율(당일0·전날50·2일전70·3일전+100, 1h grace) | 사용자 정책 확정(긴 토의). 환불 실행은 후속 PR, 모델·정책은 지금 박제 |
| 2026-07-01 (PR) | 옵션 슬롯에 `ticketName`·`unavailableReason` / 강사 제안만 위치 고정(학생 일정수정은 위치 자유) / 중복 제거 / **선택 불가는 생략 아니라 표기**(만석·시간겹침) | FE 요청(propose-options) + 사용자 토의("필터 vs 표기" — 막힌 사유 사라지면 예약가능?가 기준, 만석 표기와 일관) |

## 미해결 / 확장

- 🟡 **다회차 재설계 구현** (2026-06-28~, PR 분할): ✅PR1 엔티티 분할+1회차(pay-first), ✅좌석lock+TTL, ✅PR2+PR3 다회차 진행·완료(2+ 일정신청 `POST /enrollments/{id}/rounds`·`GET .../next-options`·**locked 게이트=직전 회차 done**·강사 **일정변경요청(`propose-slots`→`pick-slot`, 완전한 슬롯[날짜+이용권+블록], 사전수락)**·EXTRA·**완료 done**(강사 `complete`/세션 일괄 `sessions/{id}/complete`/세션일+24h 자동 sweep, hub DONE/COMPLETED 파생)). 남음: PR4 환불 상태기계·정산 연계·다이브로그.
  - **일정변경요청은 완전한 슬롯 제안** — 날짜만 바꾸면 요일(평일↔주말)이 바뀌며 daypart(이용권·입장료·블록)가 달라지므로, 강사가 이용권·블록까지 정해 제안해야 학생 선택이 곧 사전 수락(입장료는 그 슬롯 daypart 로 재산정). 위치는 회차 고정(장비 재사용).
  - **✅ 직접 일정수정(reschedule, 2026-06-28)** — 학생이 강사 제안 외 **원하는 슬롯**으로 회차를 바꾼다. **취소가 아니라 제자리 수정**(회차 id 유지, 옛 슬롯은 `EnrollmentRound.slotHistory` 적재 — "일정 변경이지 취소가 아님", CS 이력 보존). 날짜 따라 위치가 다를 수 있어 **위치·이용권·장비 재선택** 가능. 강사 미제안 슬롯이라 → **PENDING(강사 재수락)**. 흐름: `GET /enrollments/rounds/{id}/options`(그 회차 슬롯, 1회차 옵션 shape) → `POST /enrollments/rounds/{id}/reschedule`. 결제 전(PENDING)만. 제안 그대로 고르는 빠른 길 = `pick-slot`(즉시 PAYMENT_PENDING).
- 🟡 **정산** — 수수료(PG 3.4% + 플랫폼 6.6%, 실비 0%) 분해. done → 정산 연계.
- 🟢 **다이브로그**(수강생별 done 연계) · 강사 회차 메모 · 세션 단체채팅/공지 · enrollment-management 강사 검토 시트 풀 UI.
- 🟢 venue 운영 공휴일·OPEN 세분화 정밀도.

## 관련 메모리

- [[availability-domain-concept]] — coverage(예약가능시간)/session(일정) 2층 모델, 이 피처가 채우는 자리
- [[venue-domain-concept]] — availability ∩ Venue = 수강생 선택지(이 피처가 그 교집합)
- [[instructor-review-window-allows-prep]] — 강사 게이트 기조
