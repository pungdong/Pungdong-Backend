# enrollment — 수강신청 (booking)

## 1. 한 줄 요약

학생이 코스의 **첫 만남(1회차)**을 강사 가용시간 슬롯에 신청 → 강사 답변 대기 → 수락/거절. **`강사 availability ∩ Venue 운영시간` 교집합**을 처음 구현하고, availability v1 이 비워둔 점유(`PENDING`/`CONFIRMED`/`applicants[]`)를 채운다. invariant: window 가 첫 신청으로 (venue,블록) bind → **exact-match join**(같은 venue·정확히 같은 블록만 합류, 부분겹침 불가). 정책·히스토리는 [docs/features/booking.md](../features/booking.md).

## 2. 컴포넌트 지도

```mermaid
flowchart TB
  subgraph enrollment
    EC[EnrollmentController<br/>/enrollments/**]
    IEC[InstructorEnrollmentController<br/>/instructor/enrollments/**]
    OS[EnrollmentOptionsService<br/>교집합 슬롯]
    ES[EnrollmentService<br/>신청·취소]
    IES[InstructorEnrollmentService<br/>수락·거절]
    SD[BookableSlotDeriver<br/>venue 운영블록]
    WB[WindowBinder<br/>bind/unbind]
    EN[(EnrollmentJpaRepo)]
  end
  EC --> OS
  EC --> ES
  IEC --> IES
  OS --> SD
  ES --> SD
  ES --> WB
  IES --> WB
  OS --> CW[(course · availability windowRepo)]
  OS --> VR[venue.VenueRefResolver]
  OS --> VE[venue.VenueEquipmentService]
  IES -->|게이트| IA[instructorapplication]
  AV[availability.AvailabilityService] -->|window별 집계 읽기| EN
```

의존: enrollment → (account·course·availability·venue·instructorapplication). **availability → enrollment(repo, 읽기 전용)** 단방향 추가 — 캘린더가 점유를 집계하기 위함.

## 3. 흐름

### 3-1. 신청 (교집합 옵션 → PENDING + window bind)

```mermaid
sequenceDiagram
  participant S as 학생
  participant EC as EnrollmentController
  participant OS as OptionsService
  participant ES as EnrollmentService
  participant W as AvailabilityWindow

  S->>EC: GET /enrollments/options?courseId
  EC->>OS: 교집합(코스 1회차 venue × venue 운영블록 × 강사 window)
  OS-->>S: slots[](windowId·venue·블록·정원·remaining) + 장비
  S->>EC: POST /enrollments {windowId, venueRef, ticketRef, block, equipment}
  EC->>ES: submit
  ES->>ES: window 소유강사=코스강사 · 블록 ⊆ window · exact-match · 만석 · 장비 · 가격 재계산
  alt 검증 실패(만석/다른 블록/...)
    ES-->>S: 400
  else 통과
    ES->>W: 첫 active 면 bind(venueRef, sessionLabel)
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
  IES->>IES: 정원 재검증(confirmed+hold < capacity)
  IES-->>I: CONFIRMED
  Note over AV: GET /instructor/availability → window별 enrollment 집계<br/>confirmedCount/pendingCount/applicants 반영, deriveStatus
  I->>IES: POST /{id}/reject {reason}
  IES->>IES: REJECTED + 활성 0 이면 window unbind(다시 AVAILABLE)
```

## 4. 데이터 모델

```mermaid
erDiagram
  ACCOUNT ||--o{ ENROLLMENT : "student (단방향)"
  COURSE ||--o{ ENROLLMENT : "course (단방향)"
  AVAILABILITY_WINDOW ||--o{ ENROLLMENT : "availabilityWindow (단방향)"
  ENROLLMENT ||--o{ ENROLLMENT_EQUIPMENT : "equipment (cascade ALL)"

  ENROLLMENT {
    Long id PK
    Long student_id FK
    Long course_id FK
    int roundIndex "첫 만남=1"
    Long availability_window_id FK
    String venueRefId "exact-match 키"
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

**의도된 설계**: 점유의 capacity 단위는 `AvailabilityWindow`(별도 슬롯 엔티티 안 만듦). exact-match 키 = (window, venueRefId, blockStart, blockEnd) 가 enrollment 에 산다. 가격은 스냅샷(추정치). venue 운영블록은 저장 안 하고 `BookableSlotDeriver` 가 `VenueResponse`(daypart·timeBlock)에서 읽기 시 도출 — CUSTOM/OFFICIAL scope 무관.

## 5. 보안 / 권한 매트릭스

| 엔드포인트 | 인증 | 게이트 | 소유/검증 |
|---|---|---|---|
| GET `/enrollments/options` | ✅ | — | 코스 OPEN |
| POST `/enrollments` | ✅(학생) | — | window 소유강사=코스강사 · 블록⊆window · exact-match · 만석 · 장비소속 |
| GET `/enrollments/mine` | ✅ | — | 내 것만 |
| POST `/enrollments/{id}/cancel` | ✅ | — | 내 PENDING 만, 비소유=400 |
| GET `/instructor/enrollments` | ✅ | 강사신청 보유 | 내 코스 신청만 |
| POST `/instructor/enrollments/{id}/accept` | ✅ | 강사신청 | 내 코스 · PENDING · 정원 |
| POST `/instructor/enrollments/{id}/reject` | ✅ | 강사신청 | 내 코스 · PENDING |

## 6. 알려진 설계 간극

- 🟡 **결제·정산 미구현** — 수락=CONFIRMED 로 끝(디자인 풀버전은 수락→결제링크 푸시→결제완료=확정). 해결안: payment 도메인 + notification 푸시 + EnrollmentStatus 에 PAYMENT_WAITING 추가.
- 🟡 **1 window = 1 세션 단순화** — 넓은 window(여러 venue 블록 포함)는 첫 신청 블록에 bind 되어 나머지 블록이 그 window 에선 못 열림. 해결안: window 분할 또는 (window,블록)별 capacity.
- 🟢 **venue 운영 정밀도** — `BookableSlotDeriver` 는 FIXED·OPEN(단일)·SAME, WEEKLY·MONTHLY 휴무 지원. 공휴일·OPEN 세분화는 후속.
- 🟢 **가격 권위성** — 신청 스냅샷은 추정치. 강사가 입장료/장비를 바꾸면 확정/결제 시 재계산 필요(payment 도메인에서).
- 🟢 **applicants = enrollment 만** — 캘린더 슬롯 안 신청자 행은 풍덩 enrollment 만(외부 hold 는 externalCount 로만). 디자인의 external applicant 행은 후속.

## 7. 더 깊게: 테스트로 보기

- `src/test/.../usecase/EnrollmentUseCaseTest` — 실 H2 + 시큐리티 체인. 그룹 O/S/J/F/A/C/G·R. `@DisplayName` 위→아래 = 사양.
  - O1/O2: 교집합 슬롯, window 밖 블록 제외
  - S1/S2: PENDING 생성 + 캘린더 pending/applicants 반영, 장비 스냅샷
  - J1/J2: 같은 블록 합류 / 다른 블록 거절(bound window)
  - F1: 만석(confirmed=capacity) 신청 400
  - A1/A2: 수락→CONFIRMED+캘린더, 거절→REJECTED+unbind→AVAILABLE
  - C1: 취소→CANCELLED+unbind
  - G0/R1/R2/R3: 인증·게이트·격리
- REST Docs `document(...)` 컨트롤러 테스트는 venue/course/availability 와 동일하게 미작성(후속).
