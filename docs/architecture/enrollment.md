# enrollment — 수강신청 (booking)

## 1. 한 줄 요약

학생이 코스의 **첫 만남(1회차)**을 강사 예약가능시간 안의 슬롯에 신청 → 강사 답변 대기 → 수락/거절. **`강사 coverage(예약가능시간) ∩ Venue 운영블록 ∩ 코스 1회차 위치` 교집합**(venue 부가 coverage 에 통째로 ⊆)을 구현하고, availability 의 풍덩 점유(`PENDING`/`CONFIRMED`/`applicants[]`)를 채운다. invariant: 첫 신청이 (위치,블록) **session 을 생성** → **exact-match join**(같은 위치·정확히 같은 블록만 합류, 부분겹침 불가). 정책·히스토리는 [docs/features/booking.md](../features/booking.md).

## 2. 컴포넌트 지도

```mermaid
flowchart TB
  subgraph enrollment
    EC[EnrollmentController<br/>/enrollments/**]
    IEC[InstructorEnrollmentController<br/>/instructor/enrollments/**]
    OS[EnrollmentOptionsService<br/>교집합 슬롯]
    ES[EnrollmentService<br/>신청·취소 · session find-or-create]
    IES[InstructorEnrollmentService<br/>수락·거절]
    SD[BookableSlotDeriver<br/>venue 운영블록]
    EN[(EnrollmentJpaRepo)]
  end
  EC --> OS
  EC --> ES
  IEC --> IES
  OS --> SD
  ES --> SD
  OS --> CV[(availability.CoverageRepo + SessionRepo<br/>CoverageMerger)]
  ES --> CV
  OS --> CO[(course.CourseRepo)]
  OS --> VR[venue.VenueRefResolver]
  OS --> VE[venue.VenueEquipmentService]
  IES -->|게이트| IA[instructorapplication]
  AV[availability.AvailabilityService] -->|session별 집계 읽기| EN
```

의존: enrollment → (account·course·availability·venue·instructorapplication). **availability → enrollment(repo, 읽기 전용)** 단방향 추가 — 캘린더가 점유를 집계하기 위함. (옛 `WindowBinder` 제거 — session 이 생성 시점부터 위치를 소유해 bind/unbind 가 없다.)

## 3. 흐름

### 3-1. 신청 (교집합 옵션 → PENDING + session find-or-create)

```mermaid
sequenceDiagram
  participant S as 학생
  participant EC as EnrollmentController
  participant OS as OptionsService
  participant ES as EnrollmentService
  participant SE as AvailabilitySession

  S->>EC: GET /enrollments/options?courseId
  EC->>OS: 교집합(코스 1회차 venue × venue 운영블록 × 강사 coverage)
  OS-->>S: slots[]((date,venue,블록)·정원·remaining) + 장비
  S->>EC: POST /enrollments {courseId, date, venueRef, ticketRef, block, equipment}
  EC->>ES: submit
  ES->>ES: 코스 1회차 위치/이용권 · 블록이 venue 운영블록 · 블록 ⊆ 강사 coverage(containsWhole) · 만석 · 장비 · 가격 재계산
  alt 검증 실패(coverage 밖/만석/다른 위치/...)
    ES-->>S: 400
  else 통과
    ES->>SE: findOrCreateSession(date,블록,venueRef) — 첫 신청이 생성, 같은 (위치,블록)이면 join
    ES-->>S: 201 PENDING (가격 스냅샷)
  end
```

### 3-2. 강사 수락/거절 + 캘린더 반영

```mermaid
sequenceDiagram
  participant I as 강사
  participant IES as InstructorEnrollmentService
  participant AV as AvailabilityService

  I->>IES: POST /instructor/enrollments/{id}/accept
  IES->>IES: 정원 재검증(점유 OCCUPYING+hold < effectiveCapacity)
  IES-->>I: PAYMENT_PENDING (결제 대기 · 슬롯 점유 — 결제 승인이 CONFIRMED 로)
  Note over AV: GET /instructor/availability → session별 enrollment 집계<br/>confirmedCount(결제대기+확정)/pendingCount/applicants 반영, deriveStatus
  I->>IES: POST /{id}/reject {reason}
  IES->>IES: REJECTED (session 은 그대로 — 점유 0 이면 AVAILABLE)
```

## 4. 데이터 모델

```mermaid
erDiagram
  ACCOUNT ||--o{ ENROLLMENT : "student (단방향)"
  COURSE ||--o{ ENROLLMENT : "course (단방향)"
  AVAILABILITY_SESSION ||--o{ ENROLLMENT : "availabilitySession (단방향, session_id)"
  ENROLLMENT ||--o{ ENROLLMENT_EQUIPMENT : "equipment (cascade ALL)"

  ENROLLMENT {
    Long id PK
    Long student_id FK
    Long course_id FK
    int roundIndex "첫 만남=1"
    Long session_id FK
    String venueRefId "exact-match 키"
    LocalDate date "신청 날짜 스냅샷 (옛 windowId 대체)"
    LocalTime blockStart "exact-match 키"
    LocalTime blockEnd "exact-match 키"
    String ticketRef
    EnrollmentStatus status
    String rejectionReason
    int tuitionSnapshot
    int entrySnapshot
    int equipmentSnapshot
    LocalDateTime createdAt
    LocalDateTime respondedAt
  }
  ENROLLMENT_EQUIPMENT {
    Long id PK
    Long enrollment_id FK
    String itemRef
    String name
    int priceSnapshot
  }
```

**의도된 설계**: 점유의 capacity 단위는 `AvailabilitySession`(위치·시간블록·정원). 첫 신청이 `(instructor,date,venueRef,block)` session 을 find-or-create — 같은 (위치,블록)이면 join. 슬롯 식별자 = `(date, venueRefId, blockStart, blockEnd)`(옛 `availabilityWindowId` 대체 — enrollment 가 `date` 스냅샷을 가짐). 신청 자격은 그 블록이 강사 `AvailabilityCoverage` 에 통째로 ⊆(`CoverageMerger.containsWhole`) 일 때만 — coverage 는 enrollment 가 직접 읽어 검증. 가격은 스냅샷(추정치). venue 운영블록은 저장 안 하고 `BookableSlotDeriver` 가 `VenueResponse`(daypart·timeBlock)에서 읽기 시 도출 — CUSTOM/OFFICIAL scope 무관.

## 5. 보안 / 권한 매트릭스

| 엔드포인트 | 인증 | 게이트 | 소유/검증 |
|---|---|---|---|
| GET `/enrollments/options` | ✅ | — | 코스 OPEN |
| POST `/enrollments` | ✅(학생) | **본인인증(최신 VERIFIED)** — 없으면 403 -1017([identity-verification.md](identity-verification.md)) | 코스 OPEN·1회차 위치/이용권 · 블록이 venue 운영블록 · 블록⊆coverage · exact-match · 만석 · 장비소속 |
| GET `/enrollments/mine` | ✅ | — | 내 것만 |
| POST `/enrollments/{id}/cancel` | ✅ | — | 내 PENDING 만, 비소유=400 |
| GET `/instructor/enrollments` | ✅ | 강사신청 보유 | 내 코스 신청만 |
| POST `/instructor/enrollments/{id}/accept` | ✅ | 강사신청 | 내 코스 · PENDING · 정원 |
| POST `/instructor/enrollments/{id}/reject` | ✅ | 강사신청 | 내 코스 · PENDING |
| GET `/instructor/enrollments/{id}/propose-options` | ✅ | 강사신청 | 내 코스 회차만(비소유=숨김) · `ticketName`·`unavailableReason`(FULL/TIME_CONFLICT) 포함 · **위치 고정**(회차 venue 1개로 스코프) · 중복 제거 · 오늘+8주 ∩ coverage window |
| POST `/instructor/enrollments/{id}/propose-slots` | ✅ | 강사신청 | 내 코스 · PENDING · **최대 3** · bookable+좌석여유만 채택 → 좌석 보장 hold |
| POST `/enrollments/rounds/{id}/pick-slot` | ✅(학생) | — | 내 회차 · 제안목록 내 슬롯 · **hold 보장(만석 무관)** → PAYMENT_PENDING |

## 6. 알려진 설계 간극

- 🟢 **결제 연동 완료(2026-06-26)** — 수락 → `PAYMENT_PENDING`(결제 대기·슬롯 점유) → 결제 승인 → `CONFIRMED`. [payment 도메인](payment.md)(토스 결제위젯) 소유. 남은 것: notification 결제링크 푸시 · 정산 수수료 분해.
- 🟢 **venue 운영 정밀도** — `BookableSlotDeriver` 는 FIXED·OPEN(단일)·SAME, WEEKLY·MONTHLY 휴무 지원. 공휴일·OPEN 세분화는 후속.
- 🟢 **가격 권위성** — 신청 스냅샷은 추정치. 권위(청구) 금액은 결제 시점 `POST /payments/prepare` 가 재계산(수강료 라이브 + 입장료/장비 스냅샷). 입장료/장비 live 재도출은 후속([payment.md](payment.md)).
- 🟢 **applicants = enrollment 만** — 캘린더 슬롯 안 신청자 행은 풍덩 enrollment 만(외부 hold 는 externalCount 로만). 디자인의 external applicant 행은 후속.
- 🟢 **강사 제안 = 좌석 보장(hold-and-guarantee)** — propose 시 슬롯마다 `AvailabilityHold`(`proposalRoundId`·`expiresAt`) 를 잡아 학생 pick 이 만석으로 막히지 않게 한다(하드캡 우회 X — 미리 잡은 자리 사용). `proposalTtlHours`(6h) 만료 시 `EnrollmentExpiryService.sweepExpiredProposals` 가 hold 해제·제안 lapse. 정책·왜는 [docs/features/reschedule.md](../features/reschedule.md).

## 7. 더 깊게: 테스트로 보기

- `src/test/.../usecase/EnrollmentUseCaseTest` — 실 H2 + 시큐리티 체인. 그룹 O/S/J/F/A/C/G·R. `@DisplayName` 위→아래 = 사양.
  - O1/O2: 교집합 슬롯, coverage 밖 블록 제외(containsWhole 부분겹침 불가)
  - S1/S2: PENDING 생성 + session 생성 + 캘린더 pending/applicants 반영, 장비 스냅샷
  - J1/J2: 같은 (위치,블록) session 합류 / 다른 블록은 별도 session
  - F1: 만석(점유 OCCUPYING+hold=effectiveCapacity) 신청 400
  - A1/A2: 수락→PAYMENT_PENDING+캘린더(점유), 거절→REJECTED(session 잔존, 점유 0=AVAILABLE). 결제는 `PaymentUseCaseTest`
  - C1: 취소→CANCELLED
  - G0/R1/R2/R3: 인증·게이트·격리
  - G1/G2: 본인인증 게이트 — 미인증 신청 403(-1017)·아무 것도 안 생김 / 인증 후 정상 통과
- REST Docs `document(...)` 컨트롤러 테스트는 venue/course/availability 와 동일하게 미작성(후속).
