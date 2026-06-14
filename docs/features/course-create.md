# 코스 작성 (course-create)

> 피처 문서 — **정책·왜·히스토리를 소유**한다. ER·엔드포인트·필드 같은 *구현(어떻게)* 은 협력 도메인 문서로 링크만(복붙 금지).
>
> **구현 상태 (2026-06-14)**: 강사용 생성/조회/수정/상태 API 구현됨(`course` 도메인). 공개(수강생) 조회·부킹 연동은 후속.

## 한 줄

강사가 강의 상품(코스)을 만드는 화면(모바일 3-step 위저드 / 데스크탑 동일 3-step)의 BE. **기본정보**(단체·레벨·수강료·사진) + **회차**(회차별 설명·진행 위치·이용권) + 선택 **추가세션**(비용 정책). V2 의 Course 는 legacy `Lecture` 의 후신 — 장소 종속 정보(위치·입장료·장비)를 강의에서 풀던 옛 모델을 버리고, **위치/장비는 venue 도메인이 소유하고 코스는 참조만** 한다.

## 협력 도메인 (구현 출처)

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| 코스 | [course.md](../architecture/course.md) | Course aggregate · 생성/조회 API · 회차·추가세션 |
| 위치 | [venue.md](venue.md) · [architecture/venue.md](../architecture/venue.md) | `GET /venues/builder`(official+custom) · `venueRefId` 참조·검증 |
| 장비 | [architecture/venue.md](../architecture/venue.md) | 강사×위치 가격표(venue-extension) → 코스 읽기 시 합성 |
| 종목 | [discipline.md](../architecture/discipline.md) | disciplineCode 검증 |
| 자격증 카탈로그 | [Sanity](../../sanity/CLAUDE.md) | 단체·평탄화 레벨(FE 직접 읽기, BE 는 `CertLevel` enum) |
| 사진 | [course.md](../architecture/course.md) | `POST /course-images` (S3/로컬 stub, 2-phase) |
| API 계약 | [types.ts](../api-clients/types.ts) | FE 단일 출처 |

## 정책 (requirements)

### 코스 종류와 레벨
- `CourseKind` = TRIAL(체험) / CERTIFICATION(자격 과정) / TRAINING(트레이닝). **CERTIFICATION 만** 단체(`organizationCode`) + 레벨(`levels`)을 가진다 — 체험/트레이닝 칩을 자격 레벨과 섞지 않으려는 분리(chat34 "고급/중급/입문 표시 안 씀").
- 레벨은 **평탄화**(LEVEL_1~4/INSTRUCTOR) — 단체마다 명칭(예: "Advanced Freediver")은 달라도 공통 사다리. 명칭은 Sanity, BE 는 코드만.
- **레벨 2개 이상 = 패키지**(별도 토글 없음, chat45). `isPackage` 는 파생.

### 회차 · 추가세션
- 정규 회차 수 = `totalRounds`, 회차 배열 개수가 이와 일치해야 한다. **1회차(첫 만남)는 플랫폼 확정**(`platformConfirmed`).
- **회차 설명은 회차별**(공통 아님), 위치·장비는 회차 간 복사 가능(UI) — 저장은 회차마다 독립(chat25).
- **추가세션**(선택)은 정규 과정 밖 보충 — 같은 회차 구조 + **비용 정책**(무료 N회까지, 이후 회당 가격). 모델상 `EXTRA` 회차(chat19).

### 위치 · 장비 (venue 도메인 소유, 코스는 참조)
- 강사는 **"위치목록"** 하나만 요청(`GET /venues/builder`)하면 official(Sanity)+custom(내 DB)이 합쳐 온다. 코스는 고른 위치를 **`venueRefId`**("CUSTOM:&lt;pk&gt;"/"OFFICIAL:&lt;sanityId&gt;")로 가리키고, 저장 시 검증(내 custom / 캐시된 official).
- **위치별 대여 장비**는 코스가 아니라 강사×위치 가격표(venue-extension). 코스 상세는 그 가격표를 **읽기 시점 합성**해 보여준다 — 가격 바꾸면 모든 코스에 반영(코스에 복제 안 함).
- 코스는 위치의 **이용권 선택**(ticketRef × 평일/주말)만 보관. 실제 가격·시간 해석은 부킹 시점(후속).

### 상태 · 검수
- `CourseStatus` = DRAFT(임시저장) / OPEN(노출중) / CLOSED(마감). **강의별 검수 없음** — 강사 인증은 계정 단위라 강의마다 검수하지 않는다(chat43).

### 사진 · 영상
- 사진은 `POST /course-images` 로 먼저 올려 URL 을 받고(2-phase), 생성 JSON 의 `media` 에 넣는다. **이번 단계는 사진만**(S3, 로컬은 stub) — 영상은 모델에 자리(`MediaKind.VIDEO`)만, 업로드/트랜스코딩은 후속(chat25).

## 결정 히스토리

| 시점 | 결정 | PR |
|---|---|---|
| 2026-06-14 | 코스 작성 4-PR로 분할(기반부터 단계적) | #46~#49 + 본 PR |
| 2026-06-14 | **자격증 평탄화 레벨 카탈로그 = Sanity, 사진 = S3/로컬 stub** | #46 |
| 2026-06-14 | **위치 = `GET /venues/builder` 통합(official+custom) + Sanity 동기화 인프라** | #48 |
| 2026-06-14 | **장비 = 강사×위치 가격표(venue-extension), 코스 비소유** | #49 |
| 2026-06-14 | **Course 본 도메인** — 회차/추가세션(EXTRA)/상태(검수 없음)/CourseKind 로 체험·트레이닝 분리 | 본 PR |

## 미해결 / 확장

- 🟡 **공개 코스 조회·검색** — 수강생 브라우즈/상세(OPEN), legacy `/lecture/list` 대체.
- 🟡 **부킹 연동** — 회차×위치×이용권 → availability ∩ Venue 교차로 수강생 선택지, 가격·시간 해석, 장비/입장료 스냅샷 동결.
- 🟡 **자격증 (org,disc,level) 권위 검증** · **영상 업로드** · **내 강의 메트릭**(수강생·평점).
- 🟢 **legacy `Lecture` → Course 마이그레이션** (현재 공존).

## 관련 메모리

- [[course_create_roadmap]] — 4-PR 로드맵 + fork 선택(기반부터/풀 동기화/사이즈 포함).
- [[venue_domain_concept]] · [[venue_sanity_sync_design]] — 위치 1급·동기화.
- [[architecture_package_by_feature]] — package-by-feature.
