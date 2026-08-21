# CLAUDE.md — branding (브랜딩 페이지 / 내 프로필)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 합성 패키지. `account`(기본정보·프로필사진) + `instructorapplication`(승인 자격·검수 상태) + `course`(연결 강의, 후속)를 **단방향 참조**해 합성한다 — 그쪽은 이 패키지를 모른다.

## 무엇이 들어있나

- **`PublicBrandingController`** — `GET /instructors/{nickName}` + `/posts` + `/suggested` + **`/browse`** (**비로그인 가능**).
  ⚠️ **강사 목록이 셋이고 모수가 다르다** — `/instructors/public`(instructorapplication 소유, 발행을 안 봐서
  눌러도 400 인 카드가 섞인다) · `/instructors/suggested`(승인∧발행 중 무작위, 페이지네이션 불가) ·
  `/instructors/browse`(승인(그 종목)∧발행, 필터·검색·정렬되는 유일한 목록). 한 화면에서 두 숫자를 섞지 말 것.
- **`PublicBrandingPostController`** — `GET /branding-posts/{id}` (비로그인).
- **`BrandingController`** — `/branding/me/**` (인증). 조회·부분수정·발행토글·기록 교체.
- **`BrandingPostController`** — `/branding/me/posts/**` (인증). 게시물 CRUD·고정.
  🔴 **작성·수정은 레거시(구버전 앱 호환)** — 신규는 커뮤니티 통합 폼(`POST|PUT /community/posts` +
  `showOnProfile`). **숨김 토글은 여기 없다** — `PATCH /community/posts/{id}/visibility` 하나다.
- **`BrandingImageController`** — `POST /branding-images` (인증). 2-phase 업로드 1단계.
- **서비스** — `BrandingService`(프로필 합성·편집) / `BrandingPostService`(목록·상세·CRUD·미디어 lifecycle)
  / `SuggestedInstructorService`(무작위 추천) / `InstructorBrowseService`(강사 둘러보기 목록).
  **왜 강사 목록이 instructorapplication 이 아니라 여기 있나**: 모수 조건(`isPublished`)과 카드 필드
  (`tagline`·`locationLabel`)가 이 도메인 것이고 강의 수는 course 를 읽는데, branding → instructorapplication·course
  는 되지만 **반대는 순환**이다. URL 네임스페이스(`/instructors`)와 패키지 소유가 다른 건 의도다.
- **엔티티** — `AccountBranding` · `BrandingRecord` · `BrandingPost` · `BrandingPostMedia` · `BrandingPostTag`
  + `Medal`·`RecordEventCode`·`BrandingMediaKind` enum.
- **`storage/`** — `BrandingImageStorage`(S3/Local 게이트). 프로필·리뷰 이미지처럼 `S3Uploader` 를 직접 쓰면
  **로컬 폴백이 없어진다** — 같은 함정을 새로 만들지 않으려고 인터페이스를 둔다.

## 이 도메인에서 자주 틀리는 것 (핵심)

1. **강사 전용 기능이 아니다.** 일반 유저도 "내 프로필"로 똑같이 쓴다(사용자 결정 D2). 그래서 테이블이 `instructor_branding` 이 아니라 **`account_branding`** 이고, `/branding/**` 매처가 `hasRole("INSTRUCTOR")` 가 아니라 **`authenticated()`** 다. role 로 막으면 (a) 일반 유저가 못 쓰고 (b) **승인 전(pending/rejected) 강사의 편집 화면이 403** 이 된다.
2. **조회는 절대 생성하지 않는다.** `GET /branding/me` 는 미생성이면 `{exists:false}` 를 돌려줄 뿐이다. 생성은 첫 쓰기(`PATCH /branding/me`, 후속 PR 의 게시물 작성)가 한다 — GET 에 side effect 가 붙으면 프리페치·재시도·캐시가 전부 쓰기를 유발한다.
3. **`PATCH` 의 "키 생략"과 "명시적 null" 은 다른 뜻이다.** 생략 = 변경 없음, `null` = 비우기. `BrandingUpdateRequest` 가 setter 호출 여부로 이를 구분한다(`*Present` 플래그, `@JsonIgnore`).
4. **응답의 "없음"도 두 종류다.** Phase 2 미구현 필드는 **키 생략**, 유저가 지운 값은 **`null` 명시**. 강사 전용 필드(`certs`·`disciplineCodes`)만 `@JsonInclude(NON_NULL)` 을 필드 단위로 건다.
5. **`boolean isX` 는 Jackson 이 `x` 로 직렬화한다.** `isInstructor`·`isPublished` 에 `@JsonProperty` 를 명시한 이유 — 빼면 계약이 깨진다.
   ⚠️ **그런데 `@JsonProperty` 만으로는 부족하고, 원시 `boolean` 이면 오히려 키가 둘로 늘어난다.** Lombok 이 원시 `boolean isInstructor` 에 대해 만드는 게터는 `isInstructor()` 이고 Jackson 은 이걸 프로퍼티 **`instructor`** 로 본다 — 필드의 `@JsonProperty("isInstructor")` 와 **서로 다른 두 프로퍼티**가 되어 `{"instructor":true,"isInstructor":true}` 가 나간다. 래퍼 `Boolean` 이면 게터가 `getIsInstructor()` 라 프로퍼티명이 `isInstructor` 로 일치해 합쳐진다 — `MyBrandingResponse` 가 멀쩡한 게 이 차이 때문이지 설계가 아니다.
   **원시 boolean 을 쓸 거면 필드명에서 `is` 를 떼고**(`private boolean instructor`) `@JsonProperty("isInstructor")` 를 병기한다. `community` 의 `CommunityAuthorResponse` 가 그 형태다.
   🔴 **`BrandingProfileResponse.isInstructor` 는 아직 원시 boolean 이라 실제로 키가 둘 나간다**(`GET /instructors/{nickName}` 로 실측). REST Docs 가 `relaxedResponseFields` 라 문서화 안 된 여분 키를 잡아주지 못했다.
5-1. ⚠️ **`category` 와 `title` 은 독립이다 — "함께 있거나 함께 없다" 는 보장이 아니다.**
   두 컬럼 다 nullable 이고(`V19`) 묶는 제약이 없다. `BrandingPostRequest.title` 은 `category` 와
   **별개의 선택 필드**로 설계돼 있어, 클라이언트가 `{mediaUrls, title}` 만 보내면 title-only 행이 생긴다.
   지금 그런 행이 없는 건 **현재 FE 폼들이 둘 다 안 보내서**지 서버가 막아서가 아니다.
   ✅ **해소됨(2026-08-18, #285)**: `category`·`title` 이 둘 다 NOT NULL(V31) 이 되면서 title-only 행도,
   `category == null` 을 "브랜딩발 글" 로 보던 판정도 사라졌다. 되살리지 말 것 —
   관계 규칙은 [docs/features/post-surfaces.md](../../../../../../../docs/features/post-surfaces.md).

6. **`RecordEventCode`(CWT/FIM/…)는 `discipline.Discipline`(FREEDIVING/SCUBA/…)과 다른 축이다.** 둘 다 "discipline" 이라 부르면 반드시 사고 난다 — 컬럼도 `event_code`.
7. **`BrandingRecord.value` 의 컬럼명은 `record_value`** — `value` 는 H2(테스트 DB) 예약어라 그대로 쓰면 스키마 생성이 깨진다. API 필드명은 `value` 유지.
8. **숨김(`is_hidden`)은 삭제가 아니고, 이 도메인 것도 아니다.** 공개 목록·상세·게시물 수에서만 빠지고 **오너 목록엔 남는다** — 안 그러면 숨긴 글을 다시 켤 수 없다. 같은 이유로 **상세도 오너 본인에겐 열린다**(숨김·미발행 포함) — `GET /branding-posts/{id}` 는 보는 사람에 따라 갈린다. permitAll 이라 `@CurrentUser` 가 **null 일 수 있다.**
   ⚠️ **토글 엔드포인트는 커뮤니티에 있다**(`PATCH /community/posts/{id}/visibility`). 여기 있던 쌍둥이는 삭제했다 — 숨김은 두 표면에 함께 걸리는 전역 스위치인데, 이 문은 `show_on_profile=true` 만 통과시켜 커뮤니티 전용 글을 못 숨겼고 **어드민 조치(ACTIONED) 확인이 없어 신고로 내려간 글을 작성자가 되살릴 수 있었다.** 여기서 `setHidden` 을 다시 부활시키지 말 것.
9. **그리드는 미디어를 일괄 조회해 그룹핑한다.** 카드마다 `post.getMedia()` 를 건드리면 N+1 이다.
   ⚠️ **연결 강의의 DRAFT 는 공개 응답에서만 숨긴다 — 오너 상세에는 싣는다**(2026-08-18). 오너에게까지
   감췄더니 이 상세가 **수정 폼 프리필 소스**인데 `linkedCourseId` 를 못 채워, 스냅샷 교체로 **저장하는 순간
   연결이 조용히 끊겼다**(사용자는 오타만 고쳤고 에러도 없다). 요청에서 "사용자가 뗐다" 와 구별되지 않아
   클라이언트가 방어할 수도 없다. `커뮤니티`가 같은 결함을 먼저 고쳤고 도메인마다 다르게 갈 이유가 없다.
   **그리드 카드는 예외 없이 공개 규칙**(오너 개념이 없다). 안전망은 `BrandingPostUseCaseTest` L2·L3 **한 쌍** —
   되돌리려면 둘을 함께 봐야 한다.
10. **정렬·size 는 서버가 고정한다.** 클라이언트 `sort` 를 `Pageable` 에 태우면 pinned-우선이 깨지고 임의 필드 정렬이 뚫린다. `size` 상한 50.
11. **`mediaUrls` 는 우리 CDN base 로 시작하는지 검증한다.** 없으면 본문에 임의 외부 이미지를 심을 수 있고, 삭제 로직이 남의 도메인을 지우려 든다.
12. **`BrandingPostRequest.category`·`title` 은 필수다**(2026-08-18). 모든 글은 커뮤니티 글이라 분류축과 제목이 있어야 하고, DB 도 NOT NULL(V31) 이다. 예전엔 두 키를 생략하면 스냅샷 교체로 **조용히 지워졌다** — 지금은 400 이다(지워진 건 알아채기 어렵다).

## 공개 URL = 닉네임 (D3)

`GET /instructors/{nickName}`. 전용 handle 컬럼은 **폐기**됐다.

- 경로가 기존 `GET /instructors/public`(공개 강사 목록)과 한 네임스페이스다. Spring MVC 는 **리터럴 우선**이라 라우팅은 안전하지만, 닉네임이 정확히 `public` 인 계정은 프로필이 안 열린다 → 예약어로 차단(`global/validation/NickNamePolicy` — 가입·변경 시점에 400).
- **`/`·`\` 가 든 닉네임은 열 수 없다** — Spring Security `StrictHttpFirewall` 이 인코딩된 슬래시 요청을 거부한다(path traversal 방어). 방화벽을 푸는 건 하지 않는다. 신규 입력은 형식 가드로 막고, 기존 보유자는 별도 리포트 대상.
- `@PathVariable` 은 **이미 디코딩된 값**을 받는다. 추가 디코딩하면 이중 디코딩 버그.
- ⚠️ `account.nick_name` 에 **아직 UNIQUE 인덱스가 없다**(dedupe 가 유저 식별자를 바꾸는 동작이라 실데이터 점검·승인 후 별도 마이그레이션). 그래서 공개 조회 쿼리는 결정적 정렬(가장 오래된 계정) + 첫 건이다 — 단건 조회로 바꾸면 중복 시 500 이 난다.

## 작업 전

- 계약의 단일 출처는 **contract v3** (`scratchpad/branding-api-contract.md`), 제품 결정은 `branding-decisions-final.md`.
- 컨트롤러 시그니처/응답 바꾸면 **같은 PR 에서** [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신.
- 구현/ER 은 [docs/architecture/branding.md](../../../../../../../docs/architecture/branding.md), 정책·히스토리는 [docs/features/account-branding.md](../../../../../../../docs/features/account-branding.md).
- **게시물을 건드리면** 커뮤니티와 공유하는 행이다 — 관계 규칙(진리표·소유권·함정)은 [docs/features/post-surfaces.md](../../../../../../../docs/features/post-surfaces.md) 를 **먼저** 읽을 것.

## 안전망 테스트

`src/test/.../usecase/BrandingUseCaseTest` — 실 H2 + 실 시큐리티.
C* 생성 규칙(조회가 생성하지 않는다 / 첫 PATCH 가 생성) · S* 성공 · I* 강사·일반 분기 · **E* 닉네임 인코딩(한글·공백·`.`·`+` 성공, `/` 거부)** · V* 검증 · R* 권한(+ `/instructors/public` 이 가려지지 않는지).
