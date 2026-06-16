# availability — 강사 가용시간 캘린더

## 1. 한 줄 요약

강사가 가용시간(window)을 열고, 외부/수동 점유(hold)를 직접 기입해 한 캘린더에서 관리하는 도메인. **2층 모델**(window=이론적 가능성 / 점유=실제)과 **5상태 파생**(저장값 아님)이 핵심 invariant. **v1 BE-소유 코어** — 풍덩 수강생 점유(`pending`/`confirmed`/`applicants[]`)는 enrollment 도메인 산물이라 미연동(항상 0/빈 배열), 응답 모양만 forward-compatible. 정책·히스토리는 [docs/features/instructor-availability.md](../features/instructor-availability.md).

## 2. 컴포넌트 지도

```mermaid
flowchart TB
  subgraph availability
    C[AvailabilityController<br/>/instructor/availability/**]
    S[AvailabilityService<br/>recurrence 전개 · 5상태 파생 · 정원 baseline/override]
    WR[(AvailabilityWindowJpaRepo)]
    HR[(AvailabilityHoldJpaRepo)]
    W[AvailabilityWindow] --> H[AvailabilityHold]
  end
  C --> S
  S --> WR
  S --> HR
  S -->|정원 baseline 저장| AR[(account.AccountJpaRepo)]
  S -->|게이트| IA[instructorapplication<br/>existsByAccountId]
  S -->|venueRef 검증| VV[venue.VenueRefValidator]
  S -->|venueName 해석| VR[venue.VenueRefResolver]
  C -->|현재 계정| AC[account.Account<br/>defaultCapacity]
  SEC[global.security<br/>SecurityConfiguration] -.authenticated.-> C
```

의존 방향은 단방향 — availability → (account · instructorapplication · venue). 역참조 없음.

**정원 모델(요약)**: 정원은 `Account.defaultCapacity`(기본 4)에 종속. window 는 `capacityOverride==null` 이면 그 값을 라이브 참조(`effectiveCapacity = override ?? account.defaultCapacity`), 그 날만 ±로 고정하면 override. baseline 변경은 account 1 row update 뿐(override 없는 일정은 저장 없이 따라감 — 전파 write 0). 정책·왜는 [features/instructor-availability.md](../features/instructor-availability.md) "정원" 절.

## 3. 흐름

### 3-1. 가용시간 생성 (recurrence 전개)

```mermaid
sequenceDiagram
  participant I as 강사(@CurrentUser)
  participant C as AvailabilityController
  participant S as AvailabilityService
  participant IA as InstructorApplicationJpaRepo
  participant WR as AvailabilityWindowJpaRepo

  I->>C: POST /instructor/availability {mode,date,dayOfWeeks?,start,end,capacity?,venueRef?}
  C->>S: create(account, req)
  S->>IA: existsByAccountId(id)
  alt 강사신청 없음
    S-->>C: BadRequestException (400)
  else 통과
    S->>S: 시간 검증 + capacity override 검증(null=기본값 따름) + venueRef 검증(있으면)
    S->>S: expandDates(req) — ONCE 1개 / WEEKLY 1주 / FOUR_WEEKS 4주(과거일 제외)
    loop 각 날짜
      S->>WR: save(window)
    end
    S-->>C: List<WindowResponse> (status=AVAILABLE)
    C-->>I: 201 _embedded.windows[]
  end
```

### 3-2. 점유 추가 (외부예약 / ± 빠른조정)

```mermaid
sequenceDiagram
  participant I as 강사
  participant S as AvailabilityService
  participant W as AvailabilityWindow

  I->>S: POST /{id}/holds {count, memo?}
  S->>S: requireOwned(me, id) — 비소유면 400
  S->>W: addHold(count, memo) — 유효정원 초과해도 기록(자동확장 없음)
  S->>S: deriveStatus — filled>=effectiveCapacity ? FULL : EXTERNAL
  S-->>I: 201 갱신 window
```

### 3-3. 정원 조정 (계정 baseline / 일정 override)

```mermaid
sequenceDiagram
  participant I as 강사
  participant S as AvailabilityService
  participant AR as AccountJpaRepo
  participant W as AvailabilityWindow

  Note over I,W: 계정 baseline — override 없는 일정 전부에 라이브 반영
  I->>S: PATCH /settings {capacity}
  S->>AR: account.defaultCapacity = capacity (1 row)
  S-->>I: {defaultCapacity}

  Note over I,W: 일정 override — 그 일정만 고정 / 해제
  I->>S: PATCH /{id}/capacity {capacity}
  S->>W: capacityOverride = capacity
  S-->>I: 갱신 window (capacityOverridden=true)
  I->>S: DELETE /{id}/capacity
  S->>W: capacityOverride = null (기본값 따르기)
  S-->>I: 갱신 window (capacityOverridden=false)
```

## 4. 데이터 모델

```mermaid
erDiagram
  ACCOUNT ||--o{ AVAILABILITY_WINDOW : "instructor (단방향)"
  AVAILABILITY_WINDOW ||--o{ AVAILABILITY_HOLD : "holds (cascade ALL, orphanRemoval)"

  ACCOUNT {
    Long id PK
    Integer defaultCapacity "기본 정원(기본 4) — override 없는 window 의 유효정원"
  }
  AVAILABILITY_WINDOW {
    Long id PK
    Long instructor_id FK
    LocalDate date
    LocalTime startTime
    LocalTime endTime
    Integer capacityOverride "nullable — null=계정 defaultCapacity 라이브 참조 / 값=그 날만 고정"
    String venueRefId "nullable, CUSTOM:pk|OFFICIAL:id"
    String sessionLabel "nullable, 1부/오후"
    LocalDateTime createdAt
    LocalDateTime updatedAt
  }
  AVAILABILITY_HOLD {
    Long id PK
    Long window_id FK
    int count "±=1, 외부=1~N"
    String memo "null=±빠른조정, 값=외부예약"
    LocalDateTime createdAt
  }
```

**의도된 설계**: `venueRefId`/`sessionLabel` nullable — 빈 가용시간은 위치 없이 시간만. 점유는 hold 단일 테이블에 `memo` 로 두 조정 방식 흡수. `SlotStatus`·`filled`·`externalCount`·**유효정원**(`effectiveCapacity`) 는 **저장 안 함** — 읽기 시 파생. 정원의 출처는 `ACCOUNT.defaultCapacity`, `capacityOverride` 는 sparse(그 날만 다를 때만 값) — 안 건드린 일정은 계정 값을 라이브로 따라 baseline 변경에 전파 write 가 필요 없다.

**의도적 미구현**: 풍덩 enrollment 와의 관계(미래 `AVAILABILITY_WINDOW ||--o{ ENROLLMENT`)는 booking 도메인이 생길 때. v1 응답의 `confirmedCount`/`pendingCount`/`applicants[]` 는 그 자리만 잡아둔 placeholder.

## 5. 보안 / 권한 매트릭스

매처: `/instructor/availability/**` → `authenticated` (`SecurityConfiguration`). 게이트는 서비스에서.

| 엔드포인트 | 인증 | 추가 게이트 | 소유권 |
|---|---|---|---|
| POST `/instructor/availability` | ✅ | 강사신청 보유(`existsByAccountId`) | instructor=현재 계정 / capacity 주면 1 이상 |
| GET `/instructor/availability/settings` | ✅ | 강사신청 보유 | 현재 계정 defaultCapacity |
| PATCH `/instructor/availability/settings` | ✅ | 강사신청 보유 | capacity<1 = 400 |
| GET `/instructor/availability?from&to` | ✅ | — | 내 window 만 조회 |
| GET `/instructor/availability/{id}` | ✅ | — | 비소유 = 400(존재 숨김) |
| PUT `/instructor/availability/{id}` | ✅ | — | 비소유 = 400 (정원은 안 다룸) |
| PATCH `/instructor/availability/{id}/capacity` | ✅ | — | 비소유 = 400 / capacity<1 = 400 |
| DELETE `/instructor/availability/{id}/capacity` | ✅ | — | 비소유 = 400 (override 해제) |
| DELETE `/instructor/availability/{id}` | ✅ | — | 비소유 = 400 |
| POST `/instructor/availability/{id}/holds` | ✅ | — | 비소유 = 400 / count<1 = 400 |
| DELETE `/.../{id}/holds/{holdId}` | ✅ | — | 비소유/없는 hold = 400 |

## 6. 알려진 설계 간극

- 🟡 **enrollment 미연동** — `pending`/`confirmed`/`applicants[]` 는 항상 0/빈 배열. booking 도메인 생길 때 `deriveStatus` 의 confirmed/pending 인자만 채우면 동작(해결안: enrollment → window FK + 집계 주입).
- 🟡 **OFFICIAL venueRef 이름 해석** — `VenueRefResolver` 가 Sanity 캐시(`OfficialVenueCache`)에서 읽음. BE 의 OFFICIAL 동기화가 미완이면 `venueName=null` 가능(토큰은 보존). 해결안: [[venue-sanity-sync-design]].
- 🟢 **유효정원 < 점유 허용(확정 바닥)** — baseline/override 를 점유보다 낮춰도 막지 않음. 확정 점유는 유지(취소 없음), 새 신청 수락만 차단. 옛 "정원 자동확장"을 대체한 의도된 단순화.
- 🟢 **"새 baseline 을 기존 일정에 일괄 적용" 버튼 미구현** — 라이브 참조라 override 없는 일정은 자동 반영되므로 불필요하지만, override 한 일정까지 일괄로 되돌리는 액션은 후속(필요 시).
- 🟢 **겹치는 window 허용** — 같은 날 시간 겹치는 window 를 막지 않음(디자인상 1부/2부 분할 등 합법 케이스 다수). 충돌 판단은 강사 몫(디자인 원칙 "사실은 보여주고 판단은 강사가").

## 7. 더 깊게: 테스트로 보기

- `src/test/.../usecase/AvailabilityUseCaseTest` — 실 H2 + 시큐리티 체인. 그룹 S/H/C/G/R/V. `@DisplayName` 을 위에서 아래로 읽으면 사양.
  - S1~S6: ONCE/WEEKLY/FOUR_WEEKS 생성·전개 개수, 범위 읽기, 수정, 삭제
  - H1~H3: 외부예약 점유→EXTERNAL, 정원 초과해도 정원 유지→FULL, ±조정 추가/제거→AVAILABLE 복귀
  - C1~C5: 기본 정원 4, baseline 변경 라이브 전파, override 격리(baseline 무영향), override 해제, 유효정원<점유 시 확정 유지
  - G0/G1: 인증 401, 강사신청 없는 사용자 400
  - R1/R2: 남의 window 조회·점유 400
  - V1~V5: 시간 역전·정원<1·요일 빈 WEEKLY·기본정원<1·override<1 400
- REST Docs `document(...)` 컨트롤러 테스트는 venue/course 와 동일하게 미작성(후속).
