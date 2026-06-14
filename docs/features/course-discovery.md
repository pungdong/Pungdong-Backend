# 코스 둘러보기 (course-discovery)

> 피처 문서 — **정책·왜·결정 히스토리를 소유**. 구현(엔드포인트·필드·ER)은 [docs/architecture/course.md](../architecture/course.md) 로 링크만(복붙 X, drift 방지).

## 한 줄

수강생 **메인 홈 · 둘러보기** 화면이 OPEN 코스를 검색·필터·정렬해 카드로 훑는 공개(비로그인) 경험. V2 핸드오프 `features/home` 를 받치며 legacy `/lecture/list` 를 대체. **이번 범위는 강의(코스)만** — 투어·강사 둘러보기는 모델 미확정이라 제외.

## 협력 도메인 (구현 출처)

| 도메인 | 문서 | 역할 |
|---|---|---|
| course | [architecture/course.md](../architecture/course.md) | `GET /courses/browse` 공개 조회 · `CourseSpecifications` 필터 · 둘러보기 facet(`regions`·`primaryLocationName`) |
| venue | [architecture/venue.md](../architecture/venue.md) | `Region`(주소→지역 파생) · `VenueRefResolver`(venueRefId→이름/주소) |
| discipline | [architecture/discipline.md](../architecture/discipline.md) | 종목 코드 — 화면이 종목별(현재 프리다이빙만 active) |

## 정책 (requirements)

### 종목(discipline) — 필수, UI 필터 아님

종목별로 카탈로그가 크게 달라(자격 단체·레벨 체계 자체가 다름) **둘러보기는 항상 한 종목으로 진입**한다. 메인 화면 상단의 종목 select(현재 프리다이빙만 active)가 그 컨텍스트 — 필터 시트의 칩이 아니다. 따라서 `disciplineCode` 는 **필수 파라미터**(누락 시 400)이고, FE 는 필터 UI에 안 띄우더라도 호출엔 항상 채워 보낸다.

### 지역 필터 — 광역 묶음 + 주소 파생 (핵심 결정)

- **시안대로 광역으로 묶는다**: `서울·경기 / 강원 / 제주 / 부산·경남` + `전체`. 다이빙 가능 풀이 적어 시/구 단위는 과분할. (`venue.Region` enum 과 1:1.)
- **강사에게 지역을 따로 묻지 않는다** — 위치 등록 시 받은 도로명주소의 시·도 토큰에서 파생(`Region.fromAddress`). 별도 input 없음.
- **묶이지 않는 시·도(충청·전라 등) = `ETC`** — 명시 지역 필터엔 안 뜨지만 "전체"(region 생략)에는 포함. 매핑 안 된 지역의 코스가 사라지지 않게 하는 안전판.
- **왜 저장 시점 비정규화인가**: 코스 위치는 `venueRefId` 참조이고 OFFICIAL 위치 주소는 Sanity 캐시(Redis)라 **쿼리 타임 JOIN 으로 지역 필터가 불가**. 그래서 코스 저장 때 주소→지역을 풀어 `Course.regions` 에 박는다(스냅샷). 트레이드오프: OFFICIAL 위치 이사 시 코스 재저장 전까지 stale(드물어 MVP 허용).

### 종류·레벨 — 필터는 평탄(OR), 작성은 계단식 (의도적 차이)

같은 두 축(`CourseKind` 체험/트레이닝 vs `CertLevel` 자격 레벨)을 **화면 목적에 따라 다르게 표현**한다:

- **코스 작성(create)**: 계단식(cascade) — 종류를 라디오로 단일 선택(체험/자격증/트레이닝), 자격증일 때만 레벨 선택. 모델의 비대칭(자격만 레벨 가짐)을 UI 가 그대로 반영. ([course-create.md](course-create.md) "코스 종류 상호배타" 결정.)
- **둘러보기 필터(browse)**: 평탄(flat) — 시안 필터 시트는 [체험·L1·L2·L3·트레이닝]을 한 줄로 펼쳐 **멀티선택**, 결과는 합집합. 탐색 편의가 우선이라 일부러 평탄화. 필터엔 'CERTIFICATION' 칩이 없고(자격은 레벨 칩으로 직접 표현), 체험/트레이닝은 `kinds`·자격은 `levels` 로 와서 BE 가 `(kind ∈ kinds) OR (CERTIFICATION & level ∈ levels)` 로 OR 결합.

> 즉 "필터에서만 평탄화" — 작성 모델은 cascade 그대로 두고, 둘러보기 필터만 칩을 펼친다.

### 정렬 — 최신·가격만 (인기·일정 보류)

- 구현: `LATEST`(기본) · `PRICE_ASC` · `PRICE_DESC`.
- **`인기순`·`가까운 일정` 보류** — 코스에 **평점·확정 일정 신호가 아직 없다**(스케줄/부킹·리뷰는 별도 후속). 신호가 생기면 추가. 카드의 `meta`(주말·총 N회차) 중 회차수만 확정, 평일/주말 daypart 파생은 후속.

### 노출 · 응답 규약

- **OPEN 코스만** 공개(DRAFT/CLOSED 제외, 하드코딩).
- **빈 결과 = `200` + 빈 페이지** (repo 규약: 음성 결과는 에러 아님). "결과 N개"는 PagedModel 의 `page.totalElements`.
- 필터 파라미터는 전부 **비-PII** → GET querystring 정당.

### 공개 상세 — venue 합성, 입장료 = 이용권×daypart (핵심)

카드(`browse`)를 누르면 `GET /courses/{id}/detail`(공개·OPEN 만, 비OPEN/없음 400 존재 숨김). 구현·필드는 [course.md](../architecture/course.md) §5~6.

- **강사 편집용 `GET /{id}`(원본 ticketRef·daypart)와 분리**: 공개 상세는 venue 를 **합성**한다(`VenueRefResolver.resolveVenues`) — 위치명·type·주소(area)·**입장료**·장비.
- **입장료는 단일값이 아니다**: 시안은 위치별 `entry` 1개로 그렸지만 우리 모델은 입장료가 **이용권(ticket) × 평일/주말(daypart)** 로 갈린다(`VenueDaypart.fee`). 상세 "진행 위치"는 코스가 그 위치에서 쓰는 **이용권명 + daypart별 fee**(예 "일반권 (3시간) · 평일 48,000/주말 55,000"). → 디자인 수정 필요 항목(아래 결정 히스토리 + 미해결).
- **표시/안내용**: 수강료(price)는 신청 시, 입장료·대여료는 회차별 venue·장비 확정 후 별도 결제(시안 프레이밍과 일치). 상세의 입장료/장비는 범위 안내.

### 범위 밖 (이번 PR 제외 — 후속)

투어·강사 둘러보기 / 평점·리뷰 카드 / 강사 경력·자격·평점(강사 프로필 통합) / 큐레이션 배지(입문 추천)·`ACTIVE_BUCKETS`(입문체험·주말·딥 카운트) / 상세 ↔ 부킹 연동(availability∩venue·가격 스냅샷).

## 결정 히스토리

| 시점 | 결정 | 근거 / PR |
|---|---|---|
| 2026-06-14 | 둘러보기는 강의(코스)만, 투어·강사 제외 | 투어·강사 모델 미확정 (사용자 지시) |
| 2026-06-14 | 지역 = 광역 묶음 + 주소 파생(별도 input X), ETC 안전판 | 풀 희소 → 시/구 과분할. 시안 `FILTER_REGIONS` 와 1:1 |
| 2026-06-14 | 지역 facet = 저장 시점 비정규화(쿼리 JOIN X) | OFFICIAL 주소가 Sanity 캐시라 JOIN 불가 |
| 2026-06-14 | 지역은 OFFICIAL/CUSTOM 모두 **주소 파생**(Sanity 명시 region 필드 X) | region 매핑(시·도→묶음)은 BE 비즈니스 룰(필터=BE 쿼리, discipline 을 BE 테이블로 둔 것과 동일 논리). CUSTOM 이 어차피 주소 파생이라 OFFICIAL 만 명시 필드면 이원화+Sanity 3곳 계약 drift. 그룹 경계 변경도 BE 한 곳. (어드민 큐레이션 필요해지면 "선택적 region 오버라이드 필드"가 탈출구 — 후속.) |
| 2026-06-14 | 종목(disciplineCode) = 필수 파라미터, UI 필터엔 미노출 | 종목별 카탈로그가 크게 다름. 메인 상단 종목 select 가 컨텍스트(필터 칩 아님) |
| 2026-06-14 | 종류·레벨 = 필터만 평탄(OR 멀티칩), 작성은 cascade 유지 | 시안 필터가 [체험·L1~·트레이닝] 평탄 한 줄. 탐색 편의 우선 — 작성 모델은 안 건드림 |
| 2026-06-14 | 정렬 = 최신·가격만, 인기·일정 보류 | 평점·확정일정 신호 부재 |
| 2026-06-14 | 공개 상세(`GET /{id}/detail`) = venue 합성, 입장료 = 이용권×평일/주말 daypart fee | 시안의 단일 `entry` 와 모델 불일치 — 디자인 수정 요청(이용권명+daypart별 fee 표기). 강사 편집용 `GET /{id}` 와 분리(공개·OPEN 만·합성) |

## 미해결 / 확장

- 🟡 **상세 ↔ 부킹 연동** — availability∩venue·가격 스냅샷(상세 자체는 구현, 부킹 결합은 후속).
- 🟡 **상세 디자인 수정 반영** — 입장료 이용권×daypart 표기 / format(1:1·그룹)·회차당시간(hoursPerRound)·회차 label 은 모델에 없음(디자이너 전달함, 모델 필드 추가 여부 결정 대기) / 평점·강사 경력은 후속 데이터.
- 🟡 **정렬 인기·가까운 일정** — 리뷰·스케줄 도입 후.
- 🟡 **OFFICIAL 위치 이사 시 facet stale** — reconcile 잡에서 영향 코스 재계산(후속).
- 🟡 **목록 N+1** — 카드 매핑이 코스별 media/levels/regions(LAZY) 조회. fetch-join/프로젝션 최적화 후속.
- 🟢 **투어·강사 둘러보기** — 별도 피처(모델 확정 후).

## 관련 메모리

- [[course_create_roadmap]] — 코스 4-PR 로드맵 + "공개 코스 조회/검색" 후속 항목(이 피처).
- [[venue_domain_concept]] · [[venue_sanity_sync_design]] — OFFICIAL(Sanity)/CUSTOM(DB) 위치, 캐시 동기화.
