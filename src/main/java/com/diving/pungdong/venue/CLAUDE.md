# CLAUDE.md — venue (위치 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `Account`(owner) · `Discipline`(코드) · `InstructorApplication`(게이트) 를 **단방향 참조**.

## 무엇이 들어있나 — 강사 커스텀(CUSTOM) 위치만

이 BE 도메인은 **강사가 만든 커스텀 위치**(해양 세션·다이빙 포인트)만 다룬다. **공식(OFFICIAL) 수영장은 여기 없다 — Sanity authoring** (`sanity/schemas/venue.ts`). 코스 빌더 official+custom 통합은 **후속 BE 머지 엔드포인트**가 official(Sanity 서버사이드 읽기+캐시)+custom(DB)을 합쳐 반환한다(FE 소스 무지, course 생성과 함께). 현재 `GET /venues` 는 내 custom 목록(관리용), 공식 공개 표시는 FE 가 Sanity 직접.

- **컨트롤러**: `VenueController`(`/venues/**` — 커스텀 생성/관리 + 내 커스텀 목록 읽기). 어드민 컨트롤러 없음.
- **서비스**: `VenueService`(검증 + 종목 잠금 강제 + 커스텀 생성 게이트). 응답은 **트랜잭션 안에서 DTO 매핑**(LAZY 자식 보호).
- **엔티티**: `Venue`(owner·lockedDisciplineCode 필수) → `VenueTicket`(이용 옵션) → `VenueDaypart`(평일/주말) → `VenueTimeBlock` · `Venue` → `VenueClosure`. enum: `VenueType`/`DaypartKind`/`TimeMode`/`ClosureType`. 요일은 `java.time.DayOfWeek`.
- **레포**: `VenueJpaRepo.findAllByOwnerIdOrderByIdDesc` (+ `discipline.DisciplineService`, `instructorapplication.InstructorApplicationJpaRepo` 참조)
- **dto/**: `VenueCreateRequest`(중첩 Ticket/Daypart/TimeBlock/Closure), `VenueResponse`(`scope`="CUSTOM" 고정, 파생 `durationHours`)

보안 매처(`/venues/**` → authenticated)는 **`global/security/SecurityConfiguration`**. 역할이 아니라 인증인 이유: 리뷰 대기(SUBMITTED) 강사신청자는 아직 STUDENT 라서.

## 작업 전 반드시 읽기

- **[docs/features/venue.md](../../../../../../../docs/features/venue.md)** — **도메인 개념(멘탈 모델)** · 소유 분담(OFFICIAL=Sanity/CUSTOM=BE) · **캐싱·동기화·모니터링 설계**(미래 BE 가 OFFICIAL 읽을 때) · 정책·히스토리. **여기부터 읽어라.**
- **[docs/architecture/venue.md](../../../../../../../docs/architecture/venue.md)** — 구현(흐름/모델/권한)
- **[sanity/schemas/venue.ts](../../../../../../../sanity/schemas/venue.ts)** + **[sanity/CLAUDE.md](../../../../../../../sanity/CLAUDE.md)** — OFFICIAL 위치 스키마(계약). `venue.tickets[].disciplines`/`type`/daypart·closure 모양을 바꾸면 양쪽 같이 점검.
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **OFFICIAL = Sanity, CUSTOM = BE** (2026-06-13) — 공식 수영장의 시간/입장료/휴무는 잘 안 바뀌는 정적 카탈로그 + 사진 多 → CMS 패턴(certOrganization·term)에 맞음. 어드민 CRUD 를 BE 에 안 만들어도 됨. 강사 커스텀은 per-instructor 동적·비공개라 BE DB.
- **커스텀 생성 게이트 = 승인 아님, 그 종목 신청 보유**(SUBMITTED 포함) — 리뷰 동안 draft 준비를 막지 않음. 비공개라 reject 무해. → [[instructor-review-window-allows-prep]].
- **이용시간·권종 파생** — 권종은 티켓 카드 추가, 이용시간은 시간블록/키반납에서 파생(`durationHours`), 저장 안 함.
- **종목 잠금** — CUSTOM 은 `lockedDisciplineCode` 1개로 모든 티켓 강제(불일치 입력 400). 종목 코드는 `discipline.code` soft-ref.
- **없음/비소유 = 400 통일**(`ResourceNotFoundException`) — 레포에 404/409 인프라 없음. 남의 커스텀 존재를 숨김.
- **(미래) BE 가 OFFICIAL 을 읽을 때 동기화** — availability/부킹이 OFFICIAL 운영 데이터를 쓸 때 `HttpSanityVenueClient`+Redis 캐시+**read-side `_rev` 대조 reconcile**(정합성 바닥)+선택 webhook. **reconcile 잡 liveness alert 필수.** 상세 [[venue-sanity-sync-design]].

## 안전망 테스트

`src/test/.../usecase/VenueUseCaseTest` — 실 H2 + 시큐리티 체인. S(성공)/G(게이트)/V(검증)/R(권한·격리)/L(목록 필터). ⚠️ `Authorization` 헤더는 **raw JWT**(Bearer prefix 없음 — `JwtTokenProvider.resolveToken`). prefix 붙이면 401.

## 아직 안 한 것 (후속 PR)

- **BE 의 OFFICIAL(Sanity) 읽기·캐시·reconcile·webhook** — availability/부킹이 필요로 할 때. (설계는 [[venue-sanity-sync-design]] / docs/features 에 박제.)
- **코스 생성 연동** (위치 선택 → 티켓×daypart flatten) + **강사 availability ∩ Venue** 교차(수강생 선택지)
- 투어 상품화(OCEAN 다이빙 포인트 연동) · REST Docs `document(...)` 컨트롤러 테스트
