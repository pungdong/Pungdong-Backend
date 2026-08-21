# 코스 (course) 도메인

## 1. 한 줄 요약

**Course(코스)** = 강사가 만드는 강의 상품 — 기본정보(단체·레벨·수강료·사진) + **회차**(회차별 설명·진행 위치·이용권) + 선택적 **추가세션**(비용 정책). V2 코스 작성 화면의 본체이자 legacy `Lecture` 의 후신 — **legacy `Lecture` 는 2026-08-15 삭제됐고 공존은 끝났다(이전할 데이터도 없었다).** 핵심 invariant: **위치·장비를 코스가 소유하지 않는다** — 위치는 `venueRefId` 로 참조, 위치별 장비는 강사×위치 가격표에서 읽기 시점 합성. 그래서 코스는 "무엇을 가르치나 + 어디서(참조) + 어떤 이용권" 만 담는다.

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
- **카드의 강사 아바타(`instructorAvatarUrl`)는 페이지당 쿼리 1개**다. `Account.profilePhoto` 는 소유측 `@OneToOne(LAZY)` 이고 `default_batch_fetch_size: 100` 이라 한 페이지의 강사 사진이 IN 절 하나로 함께 온다 — 카드마다 나가지 않는다. (강사 프로필·추천 카드가 이미 쓰는 접근 패턴.) 이 배치 크기를 낮추면 여기가 조용히 느려진다(에러가 아니라 쿼리 수만 는다).
- **검색은 `제목 OR 강사 nickName` LIKE** (`CourseSpecifications.keywordLike`, 대소문자 무시). 사용자는 강사 이름으로 찾는데 예전엔 제목만 봐서 그 검색이 0건이었다 — 루트 `CLAUDE.md` 는 이미 "제목/강사 LIKE" 라고 적혀 있었으니 코드가 문서를 따라간 셈. 강사 조인은 **LEFT** 여야 한다(INNER 면 강사 계정이 없는 코스가 제목이 맞는데도 사라진다). 선행 `%` 와일드카드라 인덱스를 못 타므로 카탈로그가 커지면 전문검색이 필요해진다(현재 규모에선 과설계).
- **페이지 크기 상한은 `global/persistence/PageClamp`** (MAX 50 / DEFAULT 20). 둘러보기엔 원래 상한이 없어 `?size=100000` 으로 카탈로그를 통째로 긁을 수 있었다(어드민 신고 큐에서 실제로 났던 것과 같은 구멍). clamp 는 도메인 정책이 아니라 **모든 목록 엔드포인트에 같게 걸려야 하는 가드**라 도메인별 사본을 만들지 않는다.
- **둘러보기 facet 비정규화(`regions`·`primaryLocationName`)** — 코스의 위치는 `venueRefId` 참조이고 OFFICIAL 위치 주소는 Sanity 캐시(Redis)라 **쿼리 타임 JOIN 으로 지역 필터가 불가**. 그래서 저장 시점에 `venue.VenueRefResolver`(CUSTOM=DB, OFFICIAL=캐시)로 회차 위치 주소→`venue.Region`(서울·경기/강원/제주/부산·경남/ETC)을 풀어 코스에 박는다. 읽기 경로는 순수 JPA 컬럼 필터(`CourseSpecifications`, ES 안 씀). 트레이드오프: OFFICIAL 위치 이사 시 코스 재저장 전까지 stale(풀 이동은 드물어 MVP 허용, 후속 reconcile 후보).

## 5. 보안 / 권한 매트릭스

매처는 `global/security/SecurityConfiguration` — `/courses/**`·`/course-images` = authenticated (강사 트랙; 리뷰 대기 STUDENT 도 draft 준비 허용, venue 동일). **단 `GET /courses/browse`·`GET /courses/*/detail` 만 permitAll**(수강생 둘러보기·상세, `/courses/**` authenticated 규칙보다 먼저 매칭). PII 없음 → GET 무방.

| 엔드포인트 | 인증 | 소유권 |
|---|---|---|
| `GET /courses/browse` | **불필요(공개)** | OPEN + **그 종목 승인 강사**의 코스만 노출. 필터(종목·지역·종류·레벨·단체·가격)+검색(제목·강사명)+정렬+페이지. **size 상한 50/기본 20**(`PageClamp`). 빈 결과=200 |
| `GET /courses/{id}/detail` | **불필요(공개)** | OPEN + **승인 강사**만 — 그 외 400(존재 숨김). venue 합성(위치명·입장료·장비) |
| `POST /courses` | 필요 | instructor=현재 계정. venueRefId 는 내 custom / 캐시된 official 만 |
| `GET /courses/mine` | 필요 | 내 코스만 |
| `GET /courses/{id}` | 필요 | 내 코스만(편집용 원본) — 아니면 400(존재 숨김) |
| `PUT /courses/{id}` | 필요 | 내 코스만 — 스냅샷 교체 |
| `PATCH /courses/{id}/status` | 필요 | 내 코스만. **OPEN 전환만 그 종목 승인(APPROVED) 필요** — DRAFT/CLOSED 로 내리는 건 자유 |
| `POST /course-images` | 필요 | multipart → {fileURL} (사진만) |

## 6. 알려진 설계 간극 / 확장 자리

- 🔴 **`blocked_at` 은 어드민 전용 축이다**(2026-08-19, V33). 신고 조치로 세워지고 **강사가 만질 수 없다** —
  `CourseStatus`(DRAFT/OPEN/CLOSED)는 강사가 자유롭게 오가는 영업 상태라 조치를 거기 얹으면 되돌려진다.
  빠지는 곳: 둘러보기(`CourseSpecifications` 안에 **조건 없이** 박혀 있다 — `excludeSeeded` 와 달리
  호출부가 고르는 축이 아니다) · 공개 상세 · **강의 수 집계 4종**(브랜딩 `products`·커뮤니티 작성자 칩) ·
  게시물의 연결 강의 카드 2곳 · 신규 신청(슬롯 피커 + 제출 + 다음 회차).
  ⚠️ **확정·결제된 수강은 건드리지 않는다.** `enrollment.getCourse()` 를 타는 경로가 많아
  (수강 카드·환불 비율·채팅방 제목) 연관관계를 끊는 방식으로 구현하면 조용히 무너진다 —
  환불 금액까지 바뀐다. 필터는 **조회 쿼리에만**. 정책은 [features/moderation.md](../features/moderation.md).
- 🟡 **조치된 강의를 강사에게 알리지 않는다.** 강사는 "왜 아무도 안 들어오지" 를 알 수 없다 — 알림 1종 +
  내 강의 목록 표기가 후속.
- 🟡 **일정 변경·결제 준비는 `blocked_at` 도 `CLOSED` 도 보지 않는다.** 기존 구멍이고, 예약 게이트를
  한 헬퍼로 모을 때 함께 정리한다.
- ✅ **공개·판매는 그 종목 승인 강사만**(2026-08-22, `InstructorApprovalPolicy`). 강사 검수가 수동이라
  하루쯤 걸리는데, 신청자가 그동안 **강의·일정을 만들어 두는 것은 의도적으로 허용**한다 — 다만 승인 전에는
  정식 강사가 아니므로 **그 강의가 노출되면 안 된다.** 그전까지는 그 선이 코드에 없어서, **강사 신청을
  한 번도 안 한 계정도** 강의를 OPEN 해 둘러보기에 띄우고 **결제까지 받을 수 있었다.**
  게이트는 발행(OPEN 전환)과 조회·신청 **양쪽**에 건다 — 발행만 막으면 **열어 둔 뒤 반려된** 강의가
  계속 팔린다(반려는 `CourseStatus` 를 건드리지 않는다). `blocked_at` 과 같은 이유로 읽기 경로가 넷이다:
  둘러보기 · 공개 상세 · 슬롯 피커 · 신청 제출.
  ⚠️ **데모(seeded) 예외를 코드에 넣지 않았다** — 데모 노출은 `showSeededCourses` 라는 별개 축이고,
  무엇보다 prod 데모 코스는 `seeded` 표식이 누락된 이력이 있어 시드 예외가 정작 거기서 안 먹는다.
  데모 강사는 **어드민에서 실제로 승인**해 규칙을 그대로 통과시킨다.

- 🟢 **공개 둘러보기(`GET /courses/browse`) + 상세(`GET /courses/{id}/detail`) 구현** — OPEN 코스 목록/검색/필터 + 카드→상세(legacy `/lecture/list`·상세 대체). 상세는 강사용 `GET /{id}`(원본 ticketRef·daypart) 와 달리 **venue 합성**: venueRefId→`VenueResponse`(`VenueRefResolver.resolveVenues`)로 위치명·type·주소(area)·**입장료(이용권×평일/주말 daypart fee, `VenueDaypart.fee`)**·장비를 풀어 내려준다. 시안의 단일 `entry` 가 아니라 이용권명+daypart별 fee(예 "일반권 (3시간) · 평일 48,000/주말 55,000"). 평점·확정일정 등은 review/booking 도입 후속.
- 🟢 **강사 카드 인라인(`instructor`)** — 아바타·인증마크(승인 여부)·한마디(tagline)·자기소개(bio)·자격 뱃지·공개 강의 수를 상세 응답에 **합성해 싣는다**. 예전엔 `instructorName`(= 실은 닉네임) 하나뿐이라 클라이언트가 `GET /instructors/{nickName}` 을 **순차로 한 번 더** 불러야 했고, 그 엔드포인트는 프로필 미발행이면 400 이라 폴백 분기가 따라다녔다. **핵심은 그 값들이 애초에 브랜딩 소유가 아니었다는 것** — 아바타는 `account`, 인증마크·자격은 `instructorapplication` 소유고 브랜딩 행이 가진 건 tagline·bio 뿐이다. 그래서 프로필을 만든 적 없는 강사도 카드는 온다(그 둘만 null). **단 tagline·bio 는 프로필의 공개 설정을 따른다** — 유저가 비공개로 내리면 그 둘만 빠진다(비공개 = "포트폴리오를 감춘다", 그 둘이 곧 본문). 나머지는 계정·강사신청 소유라 남는다.
  합성은 `course.InstructorSummaryProvider`(인터페이스) ← `branding.CourseInstructorSummaryAdapter`(구현). **의존 방향을 지키려고 갈라 둔 것**이다 — `branding → course` 가 이미 있어(프로필의 강의 수) `course → branding` 을 더하면 순환이다. 필요한 쪽이 계약만 선언하고, 양쪽을 다 아는 `branding` 이 구현한다. ⚠️ **단건 상세 전용** — 카드 목록에 붙이려면 배치 메서드를 따로 둘 것(N+1). `instructorId`·`instructorName` 은 구버전 앱 호환으로 병기 중이며 deprecated.
- 🟡 **둘러보기 정렬 = 최신·가격만** — 시안의 `인기순`/`가까운 일정`은 코스에 평점·확정일정 신호가 아직 없어 미구현(부킹·리뷰 도입 시 추가). 카드의 `meta`(주말·총 N회차) 중 회차수만 확정, 평일/주말 daypart 파생은 후속.
- 🟡 **둘러보기 목록 N+1** — 카드 매핑이 코스별 media/levels/regions(LAZY)를 건드림(페이지 20 기준 소수 쿼리, MVP 허용). fetch-join/프로젝션은 후속 최적화.
- 🟡 **ticketRef 깊은 검증 안 함** — 회차 위치의 이용권 선택을 그대로 보관(그 위치에 실제 있는 이용권인지 미검증). 부킹/availability 연동 때 검증 + 가격·시간 해석.
- 🟡 **자격증 (org,disc,level) 권위 검증 안 함** — `organizationCode`·`levels` 를 코드로만 저장(instructor-application 관례). Sanity 카탈로그 대조는 후속.
- 🟡 **영상 업로드** — `MediaKind.VIDEO` 자리는 있으나 업로드/트랜스코딩 미구현(사진만).
- 🟡 **내 강의 메트릭** — 카드의 누적 수강생·평점은 reservation/review 연동 후.
- 🟢 **목록 N+1** — 상세만 위치별 장비 합성(목록은 빈 맵). 합성은 위치 수만큼 가격표 조회(코스당 소수).
- ~~🟢 **legacy `Lecture` 마이그레이션**~~ — ✅ 해소(2026-08-15). legacy 스택·테이블이 삭제되어 이전 대상이 없다.

## 7. 더 깊게: 테스트로 보기

`usecase/CourseCreateUseCaseTest` (실 H2 + 임베디드 Redis + stub Sanity + 시큐리티). `@DisplayName` 위→아래 = 사양:

- `S1` 자격 과정 생성 → 201·DRAFT·회차/위치 박힘 / `S2` 공식(OFFICIAL) 위치 사용
- `P1` 레벨 2개 → `isPackage=true` / `P2` 추가세션 → EXTRA 회차 + 비용 정책
- `E1` 코스 상세에서 위치별 장비가 강사×위치 가격표로부터 합성
- `V1`~`V4` 자격인데 레벨 없음 / 회차수 불일치 / 없는 종목 / 남의 custom 위치 → 400
- `T1` 승인 강사는 OPEN 전이 가능 / **`T2` 승인 전엔 준비(생성·조회·CLOSED)는 되지만 OPEN 만 400**
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
- **`I2` 승인 전 강사는 OPEN 자체가 400 / `I2-1` 다른 종목만 승인받았으면 그 종목 강의도 OPEN 불가(승인은 종목별)**
- `I1` 브랜딩 프로필 미작성 강사도 카드가 온다(tagline·bio 만 빈다) / `I2` 승인 전 = `isInstructor:false` + certs·lessonCount 키 없음(+ boolean 키 이중화 방지) / `I3` 승인 강사 = 인증마크·자격·강의 수 / `I4` tagline·bio 인라인 / `I5` **프로필 비공개(`/instructors/{nickName}` 400)여도 카드는 남는다** / `I6` 공유 기본 사진 → `avatarUrl` null
- `V1` DRAFT(미공개) 상세 400(존재 숨김) / `V2` 없는 id 400
