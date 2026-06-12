# CLAUDE.md — Sanity Studio (어드민 CMS)

이 폴더를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../CLAUDE.md). 셋업/사용은 [README.md](README.md).

> **이 BE 레포가 소유**. Sanity 는 별도 호스티드 CMS(3rd-party 클라우드, "우리 DB" 아님). Studio(스키마+config)는 어드민 편집 정의 + 독립 배포(`pnpm deploy` → `*.sanity.studio`)일 뿐 — **BE Gradle 빌드도 FE 도 이 폴더를 런타임에 안 건드린다**. FE 가 아니라 여기 있는 이유: 스키마 계약을 BE 도메인이 소유하고(아래), BE 가 `term` 을 서버사이드로 읽음(동의 박제).

## 무엇이 들어있나

- `schemas/certOrganization.ts` — 자격증 발급 단체(종목별). `code` = BE 전송값(soft ref), `name`(굵은 표시명) + `fullName`(부제 정식명칭).
- `schemas/term.ts` — 약관/동의. `key`/`version`/`contexts` 가 **consent 도메인 계약**. `version` custom validation = body 변경 시 bump 강제(관리자 실수 방어).
- `queries.ts` — GROQ 단일 출처(`orgsByDiscipline`, `termsByContext`). **FE 가 이 문자열 + projectId 를 복사**해 `@sanity/client` 로 직접 읽음(types.ts 복사 방식과 동일).
- `sanity.config.ts` / `sanity.cli.ts` — projectId `rc448mwo`, dataset `production`.

## 계약 — 바꿀 때 같이 갱신할 곳

- `term` 의 `key`/`version`/`contexts` ↔ **consent 도메인** ([../src/main/java/com/diving/pungdong/consent/CLAUDE.md](../src/main/java/com/diving/pungdong/consent/CLAUDE.md), `ConsentContext` enum, `HttpSanityTermClient` GROQ).
- `certOrganization.code` ↔ **instructor-application** 의 `organizationCode`(문자열, 한 번 정하면 불변 — 제출 데이터가 가리킴).
- `disciplines` 값 ↔ BE `discipline.code`(FREEDIVING/SCUBA…) 1:1.
- 필드/쿼리 모양 바꾸면 FE 가 복사하는 `queries.ts` 와 [../docs/api-clients/types.ts](../docs/api-clients/types.ts) 영향 — 같은 변경에서 점검.

## 불변 규칙

- **`certOrganization.code` 는 한 번 정하면 변경 금지** (제출된 자격증이 가리킴).
- **약관 의미가 바뀌면 `term.version` 반드시 bump** — 안 그러면 새 전문이 옛 버전명으로 박제됨. validation 이 1차로 막지만 운영 규율.
- dataset `production` 은 **public 읽기** — projectId 는 공개값(커밋 OK).
