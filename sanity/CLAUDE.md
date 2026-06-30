# CLAUDE.md — Sanity Studio (어드민 CMS)

이 폴더를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../CLAUDE.md). 셋업/사용은 [README.md](README.md).

> **이 BE 레포가 소유**. Sanity 는 별도 호스티드 CMS(3rd-party 클라우드, "우리 DB" 아님). Studio(스키마+config)는 어드민 편집 정의 + 독립 배포(`pnpm run deploy` → `*.sanity.studio`)일 뿐 — **BE Gradle 빌드도 FE 도 이 폴더를 런타임에 안 건드린다**. FE 가 아니라 여기 있는 이유: 스키마 계약을 BE 도메인이 소유하고(아래), BE 가 `term` 을 서버사이드로 읽음(동의 박제).

## ⚠️ 배포는 Node 22+ 로 (`nvm use 22`)

`sanity deploy`/`dataset import`/`manifest extract` 는 **반드시 Node 22+ 에서** 실행(`.nvmrc`=22, `package.json engines.node>=22` 로 핀했지만 셸 버전이 우선이니 직접 확인). Node 20.15 등 구버전은 매니페스트 추출이 `ERR_REQUIRE_ESM`(html-encoding-sniffer→@exodus/bytes) 로 실패하는데, **`Failed to extract manifest` 는 비치명적 경고라 배포가 "Success" 로 보인다** — 그러면 **스키마 매니페스트가 안 올라가고, 새 문서 타입이 익명/CDN(FE·앱) 읽기에서 `reason:permission` 으로 안 보인다**(인증·Studio 로는 보여서 진단이 한참 걸림 — FE 가 "다른 프로젝트"로 오진했던 사고). 배포 출력에서 **`✓ Extracted manifest` + `Deployed N/N schemas`** 를 반드시 확인. "Sanity 문서가 인증으론 보이는데 익명만 안 보임" → doc 엔드포인트 `reason` 으로 `permission` 확인 → 매니페스트 배포부터 의심. (2026-06-26 legalDocument 사고, 메모리 `feedback_sanity_node22_manifest`.)

## 데이터(콘텐츠) 읽기 / 쓰기 요청

콘텐츠 본문(약관/처리방침/`term`·`legalDocument`·`siteSettings` 문서값)은 **git 이 아니라 호스티드 데이터셋**(grep 안 됨)에 있다. 조회·수정 방법(read-only `SANITY_TOKEN` vs 쓰기용 Editor 토큰, `sanity exec --with-user-token`, Portable Text `_key` 패치, version-bump 규칙)은 **[data-access.md](data-access.md)** 에 박제 — "Sanity 데이터 좀 봐줘/고쳐줘" 요청은 거기부터.

## 무엇이 들어있나

- `schemas/certOrganization.ts` — 자격증 발급 단체(종목별). `code` = BE 전송값(soft ref), `name`(굵은 표시명) + `fullName`(부제 정식명칭). **`certifications[]`** = 그 단체가 발급하는 등급 카탈로그(object `certification`: `disciplineCode` + 평탄화 `level`(LEVEL_1~4/INSTRUCTOR) + `displayName`(단체 명칭)). 코스 작성 "단체→레벨" 과 강사 신청 본인 레벨이 같은 카탈로그를 GROQ(`certificationsByOrgAndDiscipline`)로 직접 읽음. BE 는 `level` 만 enum 으로 저장.
- `schemas/term.ts` — 약관/동의. `key`/`version`/`contexts` 가 **consent 도메인 계약**. `version` custom validation = body 변경 시 bump 강제(관리자 실수 방어).
- `schemas/legalDocument.ts` — **법적 고지 전문 페이지**(이용약관/개인정보처리방침/취소·환불). 웹 `/{slug}`(terms·privacy·refund) + 푸터 모달에 그대로 렌더. **`term` 과 다른 축** — `term`=화면별 동의 체크박스(BE 박제), `legalDocument`=공개 게시 전문 한 장(표시 전용, FE CDN 직접). `slug` validation 으로 terms/privacy/refund 3개로 잠금(라우트 보호). `body` 의 styles/lists/marks 집합은 FE `PortableTextBody` 렌더러와 1:1 일치(normal/h2/h3/blockquote · bullet/number · strong/em/link). 정책은 [../docs/features/consent-and-terms.md](../docs/features/consent-and-terms.md).
- `schemas/siteSettings.ts` — **사이트 전역 설정 싱글톤** (`launched`/`showSeededCourses`). 런칭/데모노출을 무배포로 토글하는 단일 스위치 — **launch-and-seeded-content 피처 계약**. FE 는 CDN 직접 읽어 배너/태그, **BE 도 서버사이드로 읽어**(`global/sitesettings/HttpSanitySiteSettingsProvider`, 캐시·fail-safe) 신청 차단·데모 필터를 강제. 정책은 [../docs/features/launch-and-seeded-content.md](../docs/features/launch-and-seeded-content.md).
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
- **BE-side 캐시 (통합/검증)** → **우리가 처리.** 바닥(TTL 또는 read-side `_rev` reconcile, 정합성 보장) + publish webhook(변경 문서만 무효화, best-effort·HMAC·dedup). 웹훅은 정합성 조건이 아니라 지연 최적화 — 유실돼도 바닥이 따라잡음. **정책 단일 출처 = [../docs/architecture/sanity-read-freshness.md](../docs/architecture/sanity-read-freshness.md)** (항목별 TTL·로컬 dev 웹훅 예외·보안). venue official reconcile 상세는 [../docs/features/venue.md](../docs/features/venue.md).

이 원칙은 cert org/term/venue/siteSettings 모두에 적용. **BE-side 캐싱/freshness 전반은 [../docs/architecture/sanity-read-freshness.md](../docs/architecture/sanity-read-freshness.md).**

## 계약 — 바꿀 때 같이 갱신할 곳

- `term` 의 `key`/`version`/`contexts` ↔ **consent 도메인** ([../src/main/java/com/diving/pungdong/consent/CLAUDE.md](../src/main/java/com/diving/pungdong/consent/CLAUDE.md), `ConsentContext` enum, `HttpSanityTermClient` GROQ).
- `legalDocument.slug`(terms/privacy/refund) / `body` block 집합(styles·lists·marks) ↔ **FE** `PortableTextBody` 렌더러 + `types.ts` `LegalDocument`. slug 추가/스타일 확장은 FE 렌더러부터(안 그러면 raw/무스타일). BE 도메인은 끼지 않음(FE-direct CDN 표시 전용).
- `certOrganization.code` ↔ **instructor-application** 의 `organizationCode`(문자열, 한 번 정하면 불변 — 제출 데이터가 가리킴).
- `certOrganization.certifications[].level`(평탄화 LEVEL_1~4/INSTRUCTOR) ↔ **course 도메인** `CertLevel` enum + `types.ts` `CertLevel` union. 레벨 코드 추가/변경 시 세 곳 같이.
- `venue.tickets[].disciplines` / `venue.type`(POOL_5M/DEEP_POOL/OCEAN) / daypart·closure 모양 ↔ **venue 도메인**([../docs/features/venue.md](../docs/features/venue.md), [../docs/architecture/venue.md](../docs/architecture/venue.md), BE `VenueResponse` 통합 응답). 미래 BE 동기화 시 `venueRevs`(`_rev` 대조) 사용.
- `disciplines` 값 ↔ BE `discipline.code`(FREEDIVING/SCUBA…) 1:1.
- `siteSettings.launched`/`showSeededCourses` ↔ **BE `global/sitesettings` (`SiteSettings`/`HttpSanitySiteSettingsProvider`)** + course 둘러보기 필터 + enrollment 게이트 + `types.ts` `SiteSettings`. 필드명 바꾸면 GROQ(`HttpSanitySiteSettingsProvider`)·BE 파싱 같이.
- 필드/쿼리 모양 바꾸면 FE 가 복사하는 `queries.ts` 와 [../docs/api-clients/types.ts](../docs/api-clients/types.ts) 영향 — 같은 변경에서 점검.

## 불변 규칙

- **`certOrganization.code` 는 한 번 정하면 변경 금지** (제출된 자격증이 가리킴).
- **약관 의미가 바뀌면 `term.version` 반드시 bump** — 안 그러면 새 전문이 옛 버전명으로 박제됨. validation 이 1차로 막지만 운영 규율.
- dataset `production` 은 **public 읽기** — projectId 는 공개값(커밋 OK).
