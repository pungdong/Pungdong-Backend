# 코스 (course) 도메인

## 1. 한 줄 요약

**Course(코스)** = 강사가 만드는 강의 상품 — 기본정보(단체·레벨·수강료·사진) + **회차**(회차별 설명·진행 위치·이용권) + 선택적 **추가세션**(비용 정책). V2 코스 작성 화면의 본체이자 legacy `Lecture` 의 후신(둘은 공존, 마이그레이션은 후속). 핵심 invariant: **위치·장비를 코스가 소유하지 않는다** — 위치는 `venueRefId` 로 참조, 위치별 장비는 강사×위치 가격표에서 읽기 시점 합성. 그래서 코스는 "무엇을 가르치나 + 어디서(참조) + 어떤 이용권" 만 담는다.

> 정책·왜·결정 히스토리는 [docs/features/course-create.md](../features/course-create.md). 이 문서는 *어떻게(구현)*.

## 2. 컴포넌트 지도

```mermaid
flowchart TB
  subgraph course["course 패키지 (BE)"]
    CC[CourseController<br/>/courses/**] --> CS[CourseService]
    CC2[CourseImageController<br/>/course-images] --> CIS[CourseImageService]
    CIS --> CST[CourseImageStorage<br/>S3/Local 게이트]
    CS --> CR[CourseJpaRepo]
    CR --> CE[(Course → Media · Round<br/>→ RoundVenue → Ticket)]
  end
  CS --> DS[discipline.DisciplineService<br/>종목 검증]
  CS --> VRV[venue.VenueRefValidator<br/>venueRefId 검증]
  CS --> VEQ[venue.equipment.VenueEquipmentService<br/>위치별 장비 합성]
  CS -. instructor 단방향 .-> ACC[account.Account]
  FE["강사 클라이언트"] -- "1. 사진 업로드: POST /course-images" --> CC2
  FE -- "2. 위치 고르기: GET /venues/builder" --> VB[venue.VenueController]
  FE -- "3. 코스 생성: POST /courses (venueRefId 참조)" --> CC

  classDef ext fill:#eef
  class DS,VRV,VEQ,ACC,VB ext
```

- course → venue·discipline·account **단방향 참조**(역방향 없음). 위치/장비 진실은 venue 도메인, 코스는 참조만.
- 2-phase: 사진을 먼저 `/course-images` 로 올려 url 을 받고, 생성 JSON 이 그 url + `venueRefId`(빌더에서 고른 위치)를 담는다.

## 3. 핵심 흐름 — 코스 생성

```mermaid
sequenceDiagram
  participant FE as 강사 클라이언트
  participant CS as CourseService
  participant DS as DisciplineService
  participant VRV as VenueRefValidator
  participant DB as H2/MySQL

  FE->>CS: POST /courses {기본정보, rounds[venues[venueRefId,tickets]], extraSession?}
  CS->>DS: getActiveByCode(disciplineCode)
  alt 없거나 비활성
    DS-->>FE: 400
  end
  Note over CS: CERTIFICATION 이면 organizationCode + levels 필수(아니면 400)<br/>rounds.size == totalRounds (아니면 400)
  loop 각 회차의 각 위치
    CS->>VRV: validate(me, venueRefId)
    alt CUSTOM 비소유 / OFFICIAL 캐시에 없음 / 토큰 깨짐
      VRV-->>FE: 400
    end
  end
  Note over CS: 1회차 platformConfirmed=true · 추가세션=EXTRA 회차+비용정책
  CS->>DB: save(Course + 자식 cascade)
  CS-->>FE: 201 CourseResponse (status=DRAFT, isPackage 파생, 위치별 장비 합성)
```

## 4. 데이터 모델

```mermaid
erDiagram
  Course ||--o{ CourseMedia : media
  Course ||--o{ CourseRound : rounds
  CourseRound ||--o{ RoundVenue : venues
  RoundVenue ||--o{ RoundVenueTicket : tickets
  Course }o--|| Account : "instructor (필수)"

  Course {
    Long id
    Long instructor_id "필수"
    String title
    enum kind "TRIAL|CERTIFICATION|TRAINING"
    String organizationCode "CERTIFICATION 만"
    String disciplineCode "discipline.code 검증"
    Set levels "CertLevel @ElementCollection, CERTIFICATION 만(>=2 ⇒ 패키지)"
    int totalRounds
    int price "부가세 포함"
    enum status "DRAFT|OPEN|CLOSED (검수 없음)"
    Set regions "Region @ElementCollection — 둘러보기 지역 필터(저장 시 주소→파생)"
    String primaryLocationName "카드 대표 위치명(저장 시 비정규화)"
  }
  CourseMedia { enum kind "PHOTO|VIDEO", String url, int sortOrder }
  CourseRound {
    enum roundKind "REGULAR|EXTRA"
    Integer roundIndex "REGULAR 1..N, EXTRA null"
    boolean platformConfirmed "1회차=true"
    Integer freeCount "EXTRA 전용"
    Integer perSessionPrice "EXTRA 전용"
  }
  RoundVenue { String venueRefId "CUSTOM:<pk>|OFFICIAL:<sanityId>", int sortOrder }
  RoundVenueTicket { String ticketRef, enum daypart "WEEKDAY|WEEKEND", int sortOrder }
```

설계 의도:
- **위치·장비 비소유** — `venueRefId` 로 venue 참조, 장비는 강사×위치 가격표(`venue.equipment`)에서 읽기 시점 합성(`CourseResponse.Venue.equipment`). 코스는 이용권 *선택*(ticketRef×daypart)만 보관 — 가격/시간 해석은 부킹 시점(reservation, 후속).
- **추가세션 = EXTRA 회차** — 별도 엔티티 대신 `roundKind` 로 구분 + 비용 정책 필드. 회차 구조 재사용.
- **`levels` 평탄화** — 단체 명칭은 Sanity, BE 는 `CertLevel` enum 만. `isPackage` 는 size>=2 파생(저장 안 함).
- **스냅샷 교체** — 수정은 `clearChildren()` + 재구성(orphanRemoval), venue/instructor-application 과 동일.
- `roundIndex` 컬럼명(‘index’ 예약어 회피).
- **둘러보기 facet 비정규화(`regions`·`primaryLocationName`)** — 코스의 위치는 `venueRefId` 참조이고 OFFICIAL 위치 주소는 Sanity 캐시(Redis)라 **쿼리 타임 JOIN 으로 지역 필터가 불가**. 그래서 저장 시점에 `venue.VenueRefResolver`(CUSTOM=DB, OFFICIAL=캐시)로 회차 위치 주소→`venue.Region`(서울·경기/강원/제주/부산·경남/ETC)을 풀어 코스에 박는다. 읽기 경로는 순수 JPA 컬럼 필터(`CourseSpecifications`, ES 안 씀). 트레이드오프: OFFICIAL 위치 이사 시 코스 재저장 전까지 stale(풀 이동은 드물어 MVP 허용, 후속 reconcile 후보).

## 5. 보안 / 권한 매트릭스

매처는 `global/security/SecurityConfiguration` — `/courses/**`·`/course-images` = authenticated (강사 트랙; 리뷰 대기 STUDENT 도 draft 준비 허용, venue 동일). **단 `GET /courses/browse`·`GET /courses/*/detail` 만 permitAll**(수강생 둘러보기·상세, `/courses/**` authenticated 규칙보다 먼저 매칭). PII 없음 → GET 무방.

| 엔드포인트 | 인증 | 소유권 |
|---|---|---|
| `GET /courses/browse` | **불필요(공개)** | OPEN 코스만 노출. 필터(종목·지역·종류·레벨·단체·가격)+정렬+페이지. 빈 결과=200 |
| `GET /courses/{id}/detail` | **불필요(공개)** | OPEN 코스만 — 비OPEN/없음 400(존재 숨김). venue 합성(위치명·입장료·장비) |
| `POST /courses` | 필요 | instructor=현재 계정. venueRefId 는 내 custom / 캐시된 official 만 |
| `GET /courses/mine` | 필요 | 내 코스만 |
| `GET /courses/{id}` | 필요 | 내 코스만(편집용 원본) — 아니면 400(존재 숨김) |
| `PUT /courses/{id}` | 필요 | 내 코스만 — 스냅샷 교체 |
| `PATCH /courses/{id}/status` | 필요 | 내 코스만 — DRAFT/OPEN/CLOSED |
| `POST /course-images` | 필요 | multipart → {fileURL} (사진만) |

## 6. 알려진 설계 간극 / 확장 자리

- 🟢 **공개 둘러보기(`GET /courses/browse`) + 상세(`GET /courses/{id}/detail`) 구현** — OPEN 코스 목록/검색/필터 + 카드→상세(legacy `/lecture/list`·상세 대체). 상세는 강사용 `GET /{id}`(원본 ticketRef·daypart) 와 달리 **venue 합성**: venueRefId→`VenueResponse`(`VenueRefResolver.resolveVenues`)로 위치명·type·주소(area)·**입장료(이용권×평일/주말 daypart fee, `VenueDaypart.fee`)**·장비를 풀어 내려준다. 시안의 단일 `entry` 가 아니라 이용권명+daypart별 fee(예 "일반권 (3시간) · 평일 48,000/주말 55,000"). 평점·강사 경력·확정일정 등은 review/booking 도입 후속(현재 instructorName 만).
- 🟡 **둘러보기 정렬 = 최신·가격만** — 시안의 `인기순`/`가까운 일정`은 코스에 평점·확정일정 신호가 아직 없어 미구현(부킹·리뷰 도입 시 추가). 카드의 `meta`(주말·총 N회차) 중 회차수만 확정, 평일/주말 daypart 파생은 후속.
- 🟡 **둘러보기 목록 N+1** — 카드 매핑이 코스별 media/levels/regions(LAZY)를 건드림(페이지 20 기준 소수 쿼리, MVP 허용). fetch-join/프로젝션은 후속 최적화.
- 🟡 **ticketRef 깊은 검증 안 함** — 회차 위치의 이용권 선택을 그대로 보관(그 위치에 실제 있는 이용권인지 미검증). 부킹/availability 연동 때 검증 + 가격·시간 해석.
- 🟡 **자격증 (org,disc,level) 권위 검증 안 함** — `organizationCode`·`levels` 를 코드로만 저장(instructor-application 관례). Sanity 카탈로그 대조는 후속.
- 🟡 **영상 업로드** — `MediaKind.VIDEO` 자리는 있으나 업로드/트랜스코딩 미구현(사진만).
- 🟡 **내 강의 메트릭** — 카드의 누적 수강생·평점은 reservation/review 연동 후.
- 🟢 **목록 N+1** — 상세만 위치별 장비 합성(목록은 빈 맵). 합성은 위치 수만큼 가격표 조회(코스당 소수).
- 🟢 **legacy `Lecture` 마이그레이션** — 공존 중. 데이터 이전·엔드포인트 전환은 별도.

## 7. 더 깊게: 테스트로 보기

`usecase/CourseCreateUseCaseTest` (실 H2 + 임베디드 Redis + stub Sanity + 시큐리티). `@DisplayName` 위→아래 = 사양:

- `S1` 자격 과정 생성 → 201·DRAFT·회차/위치 박힘 / `S2` 공식(OFFICIAL) 위치 사용
- `P1` 레벨 2개 → `isPackage=true` / `P2` 추가세션 → EXTRA 회차 + 비용 정책
- `E1` 코스 상세에서 위치별 장비가 강사×위치 가격표로부터 합성
- `V1`~`V4` 자격인데 레벨 없음 / 회차수 불일치 / 없는 종목 / 남의 custom 위치 → 400
- `R1` 남의 코스 상세 400(숨김) / `T1` 상태 OPEN 전이 / `L1` 내 강의 목록 = 내 것만

`usecase/CourseBrowseUseCaseTest` (공개 둘러보기 — 주소 박은 CUSTOM 위치로 지역 파생 검증):

- `S1` 비로그인 카드 필드(제목·강사·위치·지역·가격·썸네일) / `S2` 종목으로 좁힘
- `R1` 지역=서울·경기 필터 / `R2` ETC 는 명시 필터 제외·전체엔 포함
- `F1` 종류(체험)·레벨(LEVEL_1) / `F2` 단체 / `F3` 가격 밴드(min/max)
- `Q1` 제목 검색 / `O1` 가격 오름차순 정렬
- `V1` DRAFT 비노출(OPEN 만) / `V2` 빈 결과 = 200 빈 페이지

> ⚠️ `Authorization` 헤더는 **raw JWT**(prefix 없음). 공식 위치 캐시는 임베디드 Redis(process-전역)라 `@BeforeEach` 로 `venue:official:*` flush.

`usecase/CourseDetailUseCaseTest` (공개 상세 — 입장료 합성 검증, CUSTOM 위치 이용권 평일/주말 fee 직접 seed):

- `S1` OPEN 코스 공개 상세(비로그인) → 정체성·강사·회차
- `S2` **입장료 합성** — 위치 이용권의 평일/주말 fee 가 daypart 별로 정확(단일 entry 아님)
- `V1` DRAFT(미공개) 상세 400(존재 숨김) / `V2` 없는 id 400
