# CLAUDE.md — venue (위치 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `Account`(owner) · `Discipline`(코드) · `InstructorApplication`(게이트) 를 **단방향 참조**.

## 무엇이 들어있나 — 강사 커스텀(CUSTOM) 위치만

이 BE 도메인이 **소유**하는 건 **강사가 만든 커스텀 위치**(해양 세션·다이빙 포인트)뿐이다. **공식(OFFICIAL) 수영장의 authoring 은 여기 없다 — Sanity** (`sanity/schemas/venue.ts`). 다만 BE 는 그 공식 카탈로그를 **서버사이드로 읽어 Redis 에 캐시**하고(`venue.sync`), `GET /venues/builder` 로 official+custom 을 **합쳐 반환**한다(FE 소스 무지). `GET /venues` 는 내 custom 목록(관리용), 공식 위치의 *공개 표시*는 여전히 FE 가 Sanity 직접.

- **컨트롤러**: `VenueController`(`/venues/**` — 커스텀 생성/관리 + 내 커스텀 목록 + `/builder` 통합 목록). 어드민 컨트롤러 없음.
- **서비스**: `VenueService`(검증 + 종목 잠금 강제 + 커스텀 생성 게이트 + official 머지). 응답은 **트랜잭션 안에서 DTO 매핑**(LAZY 자식 보호).
- **엔티티**: `Venue`(owner·lockedDisciplineCode 필수) → `VenueTicket`(이용 옵션) → `VenueDaypart`(평일/주말) → `VenueTimeBlock` · `Venue` → `VenueClosure`. enum: `VenueType`/`DaypartKind`/`TimeMode`/`ClosureType`. 요일은 `java.time.DayOfWeek`.
- **레포**: `VenueJpaRepo.findAllByOwnerIdOrderByIdDesc` (+ `discipline.DisciplineService`, `instructorapplication.InstructorApplicationJpaRepo` 참조)
- **dto/**: `VenueCreateRequest`(중첩 Ticket/Daypart/TimeBlock/Closure), `VenueResponse`(custom·official 공용 — `scope` 로 구분)
- **`venueRefId` 3인방**: `VenueScope`(토큰 생성/파싱) · `VenueRefValidator`(**검증** 단일 출처 — CUSTOM=내 소유, OFFICIAL=캐시 존재) · `VenueRefResolver`(**읽기용 메타** 해석). 새 기능이 venueRefId 를 받으면 검증은 반드시 `VenueRefValidator` 로.
- **하위 패키지**: `sync/`(공식 카탈로그 읽기·캐시·reconcile·웹훅) · `equipment/`(강사×위치 대여 장비 가격표) · `favorite/`(강사별 즐겨찾기).

`Region` 은 이 패키지 소유 — **주소에서 읽을 때 파생**(`Region.fromAddress`), 저장 컬럼이 아니다. `VenueResponse.region` 과 둘러보기의 `Course.regions` 스냅샷이 **같은 함수**를 쓴다. 지역 파생 규칙을 다른 데서 복제하지 말 것. ⚠️ **행정구역이 아니라 권역 묶음**(인천→서울·경기, 울산→부산·경남)이고 **묶음 변경은 `Course.regions` 백필을 동반**한다 — 근거·분포는 [docs/features/course-discovery.md](../../../../../../../docs/features/course-discovery.md) "지역 필터".

보안 매처(`/venues/**` · `/venue-equipment/**` · `/venue-favorites/**` → authenticated)는 **`global/security/SecurityConfiguration`**. 역할이 아니라 인증인 이유: 리뷰 대기(SUBMITTED) 강사신청자는 아직 STUDENT 라서.

## 작업 전 반드시 읽기

- **[docs/features/venue.md](../../../../../../../docs/features/venue.md)** — **도메인 개념(멘탈 모델)** · 소유 분담(OFFICIAL=Sanity/CUSTOM=BE) · **캐싱·동기화·모니터링 설계**(미래 BE 가 OFFICIAL 읽을 때) · 정책·히스토리. **여기부터 읽어라.**
- **[docs/architecture/venue.md](../../../../../../../docs/architecture/venue.md)** — 구현(흐름/모델/권한)
- **[sanity/schemas/venue.ts](../../../../../../../sanity/schemas/venue.ts)** + **[sanity/CLAUDE.md](../../../../../../../sanity/CLAUDE.md)** — OFFICIAL 위치 스키마(계약). `venue.tickets[].disciplines`/`type`/daypart·closure 모양을 바꾸면 양쪽 같이 점검.
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **OFFICIAL = Sanity, CUSTOM = BE** (2026-06-13) — 공식 수영장의 시간/입장료/휴무는 잘 안 바뀌는 정적 카탈로그 + 사진 多 → CMS 패턴(certOrganization·term)에 맞음. 어드민 CRUD 를 BE 에 안 만들어도 됨. 강사 커스텀은 per-instructor 동적·비공개라 BE DB.
- **커스텀 생성 게이트 = 승인 아님, 그 종목 신청 보유**(SUBMITTED 포함) — 리뷰 동안 draft 준비를 막지 않음. 비공개라 reject 무해. → [[instructor-review-window-allows-prep]].
- **권종 = 티켓 카드** — 이용시간 표기는 이용권 name 의 "(N시간)"(어드민 입력)을 쓴다. 시간블록 자동 파생(`durationHours`)은 **제거됨**(6h 블록·5h 이용 같은 운영 사례로 신뢰 불가 — 딥스테이션 하프권).
- **종목 잠금** — CUSTOM 은 `lockedDisciplineCode` 1개로 모든 티켓 강제(불일치 입력 400). 종목 코드는 `discipline.code` soft-ref.
- **없음/비소유 = 400 통일**(`ResourceNotFoundException`) — 레포에 404/409 인프라 없음. 남의 커스텀 존재를 숨김.
- **즐겨찾기는 별도 테이블**(`venue_favorite`), `venue_equipment_extension` 에 컬럼 추가 아님 (2026-08-11) — 장비 가격표는 코스 읽기에서 금액을 합성하는 *사업 데이터*, 즐겨찾기는 *UI 선호*다. 섞으면 `GET /venue-equipment` 가 items 0개짜리 껍데기 행을 뱉고 두 기능의 수명주기가 엉킨다. **마크/해제 둘 다 멱등**(있으면 200 / 없어도 204) — 표식이라 "이미 함"은 에러가 아니다. **해제만 쿼리 파라미터**(DELETE 본문은 클라이언트·프록시가 흘림; venueRefId 는 PII 아님).
- **(미래) BE 가 OFFICIAL 을 읽을 때 동기화** — availability/부킹이 OFFICIAL 운영 데이터를 쓸 때 `HttpSanityVenueClient`+Redis 캐시+**read-side `_rev` 대조 reconcile**(정합성 바닥)+선택 webhook. **reconcile 잡 liveness alert 필수.** 상세 [[venue-sanity-sync-design]].

## 안전망 테스트

`src/test/.../usecase/` — 실 H2 + 시큐리티 체인. `VenueUseCaseTest`(커스텀 CRUD, S/G/V/R/L) · `VenueBuilderUseCaseTest`(통합 목록·종목 필터·region, B/F/R) · `VenueFavoriteUseCaseTest`(즐겨찾기, S/D/V/R/L) · `VenueEquipmentUseCaseTest` · `VenueReconcileTest` · `SanityWebhookUseCaseTest`.

⚠️ `Authorization` 헤더는 **raw JWT**(Bearer prefix 없음 — `JwtTokenProvider.resolveToken`). prefix 붙이면 401.
⚠️ 공식 위치 캐시는 임베디드 Redis(process-전역) — 캐시를 읽는 테스트는 `@BeforeEach` 로 `venue:official:*` flush.

## 아직 안 한 것 (후속 PR)

- ~~BE 의 OFFICIAL(Sanity) 읽기·캐시·reconcile·webhook~~ — **구현됨**(`venue.sync`). 설계 배경은 [[venue-sanity-sync-design]].
- **코스 생성 연동** (위치 선택 → 티켓×daypart flatten) + **강사 availability ∩ Venue** 교차(수강생 선택지)
- **picker "최근(recent)" 위치** — 지금은 안 한다. BE 엔 "코스에 넣은 위치"(`RoundVenue`)만 있고 picker 선택 이벤트가 없어, 파생하면 라벨과 의미가 어긋난다. 하려면 선택 이벤트 쓰기 경로 신설이 선행.
- 어드민 custom 오버사이트 · 투어 상품화(OCEAN 다이빙 포인트 연동) · REST Docs `document(...)` 컨트롤러 테스트
