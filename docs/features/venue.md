# 위치 (venue)

> 피처 문서 — **정책·왜·히스토리를 소유**한다. ER·엔드포인트·필드 같은 *구현(어떻게)* 은 [docs/architecture/venue.md](../architecture/venue.md) 로 링크만 (복붙 금지, drift 방지).
>
> **구현 상태 (2026-06-13)**: OFFICIAL(공식 수영장) = **Sanity authoring**(`sanity/schemas/venue.ts`), CUSTOM(강사) = **BE**(`venue` 도메인) — 둘 다 구현됨. **후속**은 BE 가 OFFICIAL 을 서버사이드로 읽는 인프라(캐시·reconcile·webhook)뿐 — availability/부킹이 필요로 할 때. 설계는 아래 "캐싱·동기화·모니터링 설계".

## 한 줄

프리/스쿠버 강의는 강의가 중심이 아니라 **장소(수영장·딥풀·해양 포인트) = Venue** 가 중심이다. 입장료·운영 시간대·이용권·정기 휴무·장비 대여처럼 **장소에 종속되는 정보를 강의에서 풀지 않고 Venue 에 모은다**. 강사는 코스를 만들 때 위치를 고르기만 하면 그 Venue 가 제공하는 시간대가 따라온다.

## 도메인 개념 (멘탈 모델) — 잃으면 안 되는 핵심

**Venue = 강의가 진행되는 "장소".** 좁게는 수영장(딥풀), 넓게는 강의를 진행할 위치/장소. 풍덩 전반의 **정말 중요한 1급 개념**이다.

### 왜 위치가 1급인가 (보통 서비스와 다른 점)
- 보통 매칭 서비스는 **강의가 중심**이고 위치는 단순 **geolocation(좌표)** 정도로만 친다.
- 그런데 프리다이빙·스쿠버는 대부분 **수영장(딥풀)에서 진행**된다. 그러면 그 수영장이 정해 둔 **시간대·휴일·입장료**가 강의 구성의 핵심 제약이 된다.
  - 입장료는 보통 **수영장 정책에 따라 평일/주말**로 나뉘고, 어떤 곳은 **하프권·종일권** 같은 권종을 둔다.
- 이걸 **강의에서 전부 풀려고 하면 너무 복잡하고 어려워진다.** → 그래서 **Venue 도메인을 정의하고, 수영장에 종속되는 대부분의 내용(시간대·휴무·입장료·이용권·장비)을 전부 Venue 에 종속**시킨다. 코스는 그걸 **참조만** 한다.

### 핵심 관계식 — Course · availability · Venue
- 코스(Course)를 만들 때 강사는 **위치를 선택하면, 그 수영장이 제공하는 시간대들이 자동으로 참조**된다.
- 강사가 강의 가능한 시간 = **availability**. (아직 미구현 — 별도 도메인 예정.)
- 그래서 핵심 공식: **`강사 availability ∩ Venue 가 제공하는 시간대` = 수강생이 코스를 신청할 때 고를 수 있는 위치·시간대.** 이 교차집합이 venue 도메인이 존재하는 궁극적 이유.

### 두 종류의 Venue
- **공식(OFFICIAL) 위치** — 대부분의 수영장은 **어드민이 공식 위치로 만든다.** 그러면 **모든 강사가 코스를 구성할 때 참조**할 수 있다.
- **커스텀(CUSTOM) 위치** — 해양 세션·기타 등은 **강사가 직접 추가**한다. 이건 모든 강사에게 오픈되는 게 아니라 **만든 강사에게만 종속**된다. 만든 강사는 자기 코스 구성 때 자신의 커스텀 위치를 쓸 수 있지만, **다른 강사에게는 (코스 만들 때) 노출되지 않는다.** → 강사들이 위치를 스스로 관리해 나가도록.

### 미래: 다이빙 포인트 → 투어 상품
- 특히 **다이빙 포인트** 같은 곳이 커스텀 위치로 만들어진다. 나중에 **투어 상품을 구성하면 이 개념이 자연스럽게 활용**된다 — dive point 를 정의하고, 투어 상품 만들 때 그 위치를 연동하면 되니까. (투어 상품화 자체는 후속.)

> 위 개념은 메모리 [[venue-domain-concept]] 에도 박제. 구현/소유 방식은 아래, 동기화는 "캐싱·동기화·모니터링 설계".

## 소유 모델 — OFFICIAL = Sanity, CUSTOM = BE (결정 2026-06-13)

| | 어디서 소유 | 누가 authoring | 왜 |
|---|---|---|---|
| **OFFICIAL** (공식 수영장: 딥스테이션 등) | **Sanity** (`sanity/schemas/venue.ts`) | 어드민 (Sanity Studio) | 어드민 큐레이션 + 잘 안 바뀌는 정적 정보(이용시간·입장료·휴무) + 사진/영상 다수 → `certOrganization`·`term` 과 동일 CMS 패턴. 사진은 Sanity 에셋이 해결. 어드민 CRUD 를 BE 에 안 만들어도 됨 |
| **CUSTOM** (강사 해양 포인트·다이브 포인트) | **BE DB** | 강사 (`POST /venues`) | per-instructor 동적·비공개 자산. 강사는 Sanity 접근 없음 → BE 소유 필수 |

핵심 분담: **Sanity = "수영장 정보"(이용시간·입장료·휴무·사진 같은 정적 카탈로그)** / **BE = "비즈니스 동적 정보"**(커스텀 위치 · 코스↔위치 연동 soft-ref · availability · 부킹). disciplines 는 `discipline.code` 로 soft-ref(BE 소유) — Sanity venue 가 코드로 가리킴.

### 관리 surface 분담 (어디서 관리하나)
- **OFFICIAL** — **Sanity Studio** 에서 추가·편집·관리(공개 카탈로그라 CMS 가 제격).
- **CUSTOM** — **BE admin**(후속) 에서 오버사이트: 전체 가시성·검색·"빠진 수영장 official 승격"·투어 패턴 분석. custom 은 private/UGC 라 BE DB 에 있고, 어드민은 BE admin 으로 들여다본다(Sanity 에 안 올린다).

### 읽기 경로 — 목적별 둘 (FE 는 데이터 소스를 모른다)
- **공식 위치 공개 표시**(수영장 share 페이지·브라우즈) → FE 가 Sanity 를 GROQ 로 직접(`certOrganization`/`term` house 패턴, CDN 이 publish 시 purge → fresh).
- **코스 빌더 official+custom 통합**(선택→검증) → **BE 단일 머지 엔드포인트**가 official(Sanity 서버사이드 읽기+캐시)+custom(DB)을 합쳐 반환. **FE 는 소스를 모른다**(데이터 출처는 BE 구현 디테일). 표시·검증이 같은 BE 캐시 뷰를 보게 돼 정합성도 좋다.

> 왜 BE 가 official 을 또 읽나? OFFICIAL 의 시간/휴무는 단순 표시 콘텐츠가 아니라, course 저장 시 **BE 가 클라이언트 제출값을 믿지 않고 직접 읽어 검증**해야 하는 운영 데이터(부킹·availability·가격). cert org/term(코드만 저장, 재검증 X)과 다른 점. BE 가 어차피 읽으므로 통합 read 로 노출하는 게 자연스럽다.
>
> 타이밍: 통합 엔드포인트는 BE-Sanity-read+캐시+reconcile 인프라(아래)를 끼고 **course 생성/availability 와 함께** 구축. 지금 `GET /venues` 는 "내 custom 목록"(관리용).

## 캐싱·동기화·모니터링 설계 (결정 2026-06-13) — 모니터링 설계 시 필독

BE 는 OFFICIAL venue 를 Sanity 에서 읽어 **Redis 에 캐싱**(availability/부킹 계산용). 어드민이 Sanity 에서 수정(예: 비정기 휴무 추가)했을 때 그 캐시를 어떻게 fresh 하게 유지하느냐:

### 정합성의 바닥 = read-side `_rev` 대조 (정석)
- BE 가 캐싱할 때 각 venue 의 Sanity `_rev`(리비전) 도 저장. 백스톱 reconcile 잡이 주기적으로 `*[_type=="venue"]{_id, _rev}` **만** 질의(문서당 ~수십 바이트, 전체 1~2KB, API CDN) → 캐시된 `_rev` 와 비교 → **바뀐 것만** 재fetch. full-set 비교라 **수정·추가·삭제 3개 다** 잡힘.
- 이게 **ground-truth 비교**("내 캐시 == Sanity 현재 published `_rev`?")라 **정합성의 단일 보장**. webhook 신뢰도에 정합성이 안 걸린다.
- 비용: 리비전 토큰만 비교 → 바이트 단위. 5~15분 주기로 돌려도 사실상 공짜(예전 1h TTL = 안 바뀐 *전체 문서* 재fetch 였던 낭비와 성격이 다름). **TTL 기반 주기 전체 재fetch 는 폐기.**

### 지연 최적화 = GROQ webhook (선택, near-instant)
- Sanity GROQ-powered webhook 이 publish 시 BE 로 거의 즉시 POST → 해당 venue 캐시 evict. 정합성의 *조건*이 아니라 "≤reconcile주기"를 "≤1초"로 줄이는 **지연 최적화**. 비정기 휴무에 sub-minute 즉시성이 필요 없으면 webhook 없이 reconcile-only 도 정당.
- 채택 시: endpoint 1개 + HMAC 서명검증 + idempotency-key dedup + 즉시 2xx ack(evict 는 async). **write-back ack(synced 플래그)는 안 함** — BE 모니터링 alert 와 중복이고 write token·루프 방지·레이스를 떠안을 이유가 없음(read-side 대조가 상위호환).

### 모니터링·알림 요구사항 (설계 시 반드시 반영)
- 🔴 **reconcile 잡 liveness heartbeat alert (타협 불가)** — reconcile 가 정합성의 바닥이라, 그 잡이 조용히 죽으면 stale 가 무한정 간다. "N주기 동안 reconcile 미실행" 이면 알림. **개별 실패 alert 만으로는 부족** — 잡 생존 자체를 감시.
- 🟡 refetch 실패율 / webhook 처리 실패(서명검증·refetch) 알림.
- 🟢 reconcile 가 불일치를 발견하는 빈도 = webhook 유실 신호(정상이어도 0 에 가까워야 함) — 추세 모니터링.
- 잔여 staleness 바운드 = **reconcile 주기**(드문 webhook 유실 시). 주기가 곧 최악 staleness.

### 왜 이 방향 (요약)
정합성 정석(ground-truth) + 비용 바이트 단위 + 구멍은 heartbeat 로 차단 + write 결합/루프 회피. 더 강한 실시간이 필요해지면(부킹 민감도↑) Sanity Live Content API(`client.live.events()`) 지속 구독으로 승급 — 끊기면 `_rev` 대조로 reconcile.

## 정책 (그 외)

### 커스텀 위치 생성 게이트 — 승인 아님
- 커스텀 위치 생성은 **강사 승인(APPROVED)을 요구하지 않는다**. 그 종목 강사 신청을 **보유**(상태 무관 — 리뷰 대기 SUBMITTED 포함)하면 생성 가능. 매처도 `/venues/**` = `INSTRUCTOR` 역할이 아니라 **`authenticated`**(대기자는 아직 STUDENT).
- 이유: 리뷰 1~2일 동안 draft 준비(커스텀 위치·강의·프로필)를 막으면 온보딩이 끊긴다. 커스텀은 비공개라 reject 돼도 노출 없음. → 메모리 [[instructor-review-window-allows-prep]].

### 이용권·시간 모델 (한 카드 = 한 이용권)
- 한 이용권 카드(일반권/하프권/종일권)에 3축: ① 평일/주말 입장료 ② 시간 — 고정 시간대 vs **상시 입장**(오픈~클로즈 + 키반납 N시간) ③ 주말 — 동일/다름/상시. 권종은 새 축이 아니라 카드 추가. 이용시간은 시간블록/키반납에서 **파생**(저장 안 함).
- **정기 휴무**는 위치 공통(권종 무관) — 매주 + 월간 동시 가능. 월간은 **atomic**("N째 주 X요일" 1건); "2·4주 수" 나 "2주 화 + 4주 목"은 휴무 항목을 여러 개로(grouping 은 UI 표현, 저장은 원자 단위 → 모델 균일·평가 단순).

### 투어·다이빙 포인트
- 투어 상품은 별도 모델이 아니라 다이빙 포인트를 `type=OCEAN` 커스텀 Venue 로 정의 → 투어 구성 시 연동. (상품화 자체는 후속.)

## 협력 도메인

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| venue (BE) | [venue.md](../architecture/venue.md) | CUSTOM 위치 모델·API · OFFICIAL Sanity 읽기/캐시/동기화 · 코스 빌더 통합 읽기 |
| **Sanity Studio** | [sanity/CLAUDE.md](../../sanity/CLAUDE.md) | OFFICIAL 수영장 authoring 스키마(`venue.ts`) + GROQ |
| discipline | [discipline.md](../architecture/discipline.md) | 이용권 대상 종목 코드 검증·soft-ref |
| instructor-application | [instructor-application.md](../architecture/instructor-application.md) | 커스텀 위치 생성 게이트(그 종목 신청 보유) |
| account | — | 커스텀 위치 owner |
| (미래) availability / course | — | Venue 시간대 ∩ 강사 가용 = 수강생 선택지 |

## 결정 히스토리

| 시점 | 결정 | 근거 |
|---|---|---|
| 2026-06-12 | Venue 를 1급 도메인으로 도입 | 장소 종속 정보를 강의에서 풀면 너무 복잡 |
| 2026-06-12 | 커스텀 생성 게이트 = 승인 아님, 그 종목 신청 보유(PENDING 포함) | 리뷰 동안 draft 준비를 막지 않음. 비공개라 reject 무해 |
| 2026-06-13 | **OFFICIAL = Sanity authoring, CUSTOM = BE** | 수영장 정적 정보는 CMS 패턴(certOrg·term)에 맞고 사진=Sanity 에셋. 어드민 CRUD 불필요 |
| 2026-06-13 | **동기화 = read-side `_rev` 대조(정합성 바닥) + 선택 webhook, TTL 폐기, write-back ack 안 함** | `_rev` 대조가 ground-truth·바이트 단위. webhook 은 지연 최적화. ack 플래그는 BE 모니터링과 중복 |
| 2026-06-13 | **reconcile 잡 liveness heartbeat alert 필수** | reconcile 가 정합성의 바닥 — 잡이 죽으면 무한 stale |

## 미해결 / 확장

- 🟡 **BE 의 OFFICIAL(Sanity) 읽기 인프라** — `HttpSanityVenueClient`(읽기/Redis 캐시) + reconcile 잡 + (선택)webhook endpoint. availability/부킹이 OFFICIAL 운영 데이터를 BE 에서 쓸 때 추가(지금은 FE 가 Sanity 직접 읽기로 충분). (BE 커스텀 도메인 + Sanity venue 스키마는 구현 완료.)
- 🟡 **코스 생성 연동 + availability 교차** — 위치 선택 → 티켓×daypart flatten, availability ∩ Venue. availability 도메인과 함께.
- 🟢 **부킹 민감도↑ 시 Sanity Live Content API 승급** — 지속 구독 실시간.
- 🟢 학생/공개 Venue 읽기(코스 상세) · 투어 상품화.

## 관련 메모리

- [[instructor-review-window-allows-prep]] — 리뷰 대기 중 준비(커스텀 위치·draft) 허용.
- (추가 예정) venue OFFICIAL=Sanity + read-side `_rev` 동기화 + reconcile heartbeat 결정.
