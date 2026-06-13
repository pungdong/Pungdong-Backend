# CLAUDE.md — Sanity Studio (어드민 CMS)

이 폴더를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../CLAUDE.md). 셋업/사용은 [README.md](README.md).

> **이 BE 레포가 소유**. Sanity 는 별도 호스티드 CMS(3rd-party 클라우드, "우리 DB" 아님). Studio(스키마+config)는 어드민 편집 정의 + 독립 배포(`pnpm deploy` → `*.sanity.studio`)일 뿐 — **BE Gradle 빌드도 FE 도 이 폴더를 런타임에 안 건드린다**. FE 가 아니라 여기 있는 이유: 스키마 계약을 BE 도메인이 소유하고(아래), BE 가 `term` 을 서버사이드로 읽음(동의 박제).

## 무엇이 들어있나

- `schemas/certOrganization.ts` — 자격증 발급 단체(종목별). `code` = BE 전송값(soft ref), `name`(굵은 표시명) + `fullName`(부제 정식명칭). **`certifications[]`** = 그 단체가 발급하는 등급 카탈로그(object `certification`: `disciplineCode` + 평탄화 `level`(LEVEL_1~4/INSTRUCTOR) + `displayName`(단체 명칭)). 코스 작성 "단체→레벨" 과 강사 신청 본인 레벨이 같은 카탈로그를 GROQ(`certificationsByOrgAndDiscipline`)로 직접 읽음. BE 는 `level` 만 enum 으로 저장.
- `schemas/term.ts` — 약관/동의. `key`/`version`/`contexts` 가 **consent 도메인 계약**. `version` custom validation = body 변경 시 bump 강제(관리자 실수 방어).
- `schemas/venue.ts` — **공식(OFFICIAL) 위치(수영장/딥풀)**. 어드민 authoring, 강사 코스 빌더가 참조하는 정적 카탈로그. 이용권(평일/주말·고정/상시·키반납)·정기휴무·사진/영상·장비정보. `tickets[].disciplines` = BE `discipline.code` soft-ref. 시간은 `"HH:mm"` 문자열. 오브젝트 타입(`venueTicket`/`venueDaypart`/`venueTimeBlock`/`venueClosure`)도 같이 등록. **강사 커스텀 위치는 Sanity 아님 — BE DB**(`venue` 도메인). 도메인/정책은 [../docs/features/venue.md](../docs/features/venue.md).
- `queries.ts` — GROQ 단일 출처(`orgsByDiscipline`, `termsByContext`, `officialVenuesByDiscipline`, `venueById`, `venueRevs`). **FE 가 이 문자열 + projectId 를 복사**해 `@sanity/client` 로 직접 읽음(types.ts 복사 방식과 동일) — **공식 위치 공개 표시**용(share/브라우즈). 코스 빌더 official+custom 통합은 후속 **BE 머지 엔드포인트**(FE 소스 무지). custom 위치는 private 라 Sanity 아님(BE DB).
- `sanity.config.ts` / `sanity.cli.ts` — projectId `rc448mwo`, dataset `production`.

## 읽기 기조 (설계 원칙)

**Sanity 에만 있는 공개 정보는 FE 가 Sanity CDN 을 직접 읽는다. BE 통합/검증이 필요한 것만 BE 에 위임해 API 로 읽는다(FE 는 데이터 소스를 모른다).**

- **기본 = FE-direct (CDN).** 공개·읽기 위주·잘 안 바뀌는 콘텐츠(cert org·term·venue official)는 FE 가 `@sanity/client` 로 `useCdn: true` 직접 읽기. CDN 캐시 읽기는 **unlimited rate + API 요청 한도 비카운트**(비-CDN 직접 API 대비 10× 저렴) → 트래픽이 Sanity 엣지로 빠져 BE 를 안 탄다. 많이 요청될수록 CDN 이 유리.
- **BE 경유 = 통합/검증이 필요할 때만.** 여러 소스 결합(official+custom)·권위 검증(course 저장 시 official 값 재검증)·private 결합처럼 BE 가 끼어야 할 때만 BE 가 Sanity 를 서버사이드로 읽어 **캐싱(reconcile)** 후 API 반환. 이 경우만 `useCdn` 무관(권위/실시간).
- **하지 말 것**: 공개 표시까지 BE 로 프록시 통일. Sanity CDN 이 하던 일을 BE compute/bandwidth + 캐시 유지로 옮기는 꼴(= CDN 재발명, 비용·운영 증가).
- 필수: FE 직접 읽기는 **`useCdn: true`**. (queries.ts 예시 참고.)

### freshness — 두 캐시, 두 주인
변경(어드민 publish) 반영 속도 처리는 **읽기 경로마다 캐시 주인이 달라** 따로 간다:
- **FE-direct CDN (공개 표시)** → **Sanity 가 자동 처리.** CDN 캐시는 **publish mutation 시 flush(purge-on-publish)** → 다음 읽기는 새 내용(보통 수 초, stale-while-revalidate 로 잠깐 직전값 후 수렴). **우리가 할 일 0.** "보장된 즉시"가 필요하면 그 읽기만 `useCdn:false`(origin·느림·차감·10×) 또는 Live Content API(실시간 push) — venue 엔 과함.
- **BE Redis (통합/검증)** → **우리가 처리.** read-side `_rev` 대조 reconcile(정합성 바닥) + 선택 webhook + **reconcile 잡 liveness alert 필수**. ([../docs/features/venue.md](../docs/features/venue.md) "캐싱·동기화·모니터링 설계".)

이 원칙은 cert org/term/venue 모두에 적용. venue 통합 read(BE 머지)·official 캐싱·동기화 상세는 [../docs/features/venue.md](../docs/features/venue.md).

## 계약 — 바꿀 때 같이 갱신할 곳

- `term` 의 `key`/`version`/`contexts` ↔ **consent 도메인** ([../src/main/java/com/diving/pungdong/consent/CLAUDE.md](../src/main/java/com/diving/pungdong/consent/CLAUDE.md), `ConsentContext` enum, `HttpSanityTermClient` GROQ).
- `certOrganization.code` ↔ **instructor-application** 의 `organizationCode`(문자열, 한 번 정하면 불변 — 제출 데이터가 가리킴).
- `certOrganization.certifications[].level`(평탄화 LEVEL_1~4/INSTRUCTOR) ↔ **course 도메인** `CertLevel` enum + `types.ts` `CertLevel` union. 레벨 코드 추가/변경 시 세 곳 같이.
- `venue.tickets[].disciplines` / `venue.type`(POOL_5M/DEEP_POOL/OCEAN) / daypart·closure 모양 ↔ **venue 도메인**([../docs/features/venue.md](../docs/features/venue.md), [../docs/architecture/venue.md](../docs/architecture/venue.md), BE `VenueResponse` 통합 응답). 미래 BE 동기화 시 `venueRevs`(`_rev` 대조) 사용.
- `disciplines` 값 ↔ BE `discipline.code`(FREEDIVING/SCUBA…) 1:1.
- 필드/쿼리 모양 바꾸면 FE 가 복사하는 `queries.ts` 와 [../docs/api-clients/types.ts](../docs/api-clients/types.ts) 영향 — 같은 변경에서 점검.

## 불변 규칙

- **`certOrganization.code` 는 한 번 정하면 변경 금지** (제출된 자격증이 가리킴).
- **약관 의미가 바뀌면 `term.version` 반드시 bump** — 안 그러면 새 전문이 옛 버전명으로 박제됨. validation 이 1차로 막지만 운영 규율.
- dataset `production` 은 **public 읽기** — projectId 는 공개값(커밋 OK).
