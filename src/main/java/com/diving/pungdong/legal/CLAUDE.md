# CLAUDE.md — legal (법적 고지 프록시)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. 이용약관/개인정보처리방침/취소·환불 **전문 페이지**를 공개 제공. 상태/엔티티 없음 — Sanity 읽기 프록시 + 캐시뿐.

## 무엇이 들어있나

- **컨트롤러**: `LegalController` — `GET /legal/{slug}`(slug = terms | privacy | refund). 공개(permitAll), 없으면 404.
- **경계**: `LegalDocumentClient`(interface) + `HttpSanityLegalDocumentClient`(impl) — Sanity `legalDocument` 를 GROQ 로 조회. slug 당 짧은 TTL 캐시(기본 5분) + fail-safe(실패 시 마지막 캐시값). 테스트는 인터페이스를 `@MockBean`.
- **dto/**: `LegalDocumentResponse`{slug, title, **body(JsonNode passthrough)**, version, effectiveDate}.

보안 매처(`GET /legal/**` → permitAll)는 `global/security/SecurityConfiguration`. Sanity 설정은 `application.yml` 의 `pungdong.sanity.*`.

## 왜 BE 프록시인가 (★ 핵심 — 임시 우회)

원래 설계는 **FE 가 Sanity CDN 을 익명 직접 읽기**([sanity_read_principle] 기조). 그런데 이 Sanity 프로젝트(rc448mwo/production)는 **2026-06-11(셋업) 이후 생성된 문서를 익명 읽기에서 거부**한다(`reason:permission`, **생성일 기반** — 타입·생성방법 무관, venue 등 옛 문서는 읽힘). 비-Enterprise 플랜이라 콘솔로 못 풀어 **Sanity 지원 문의 중**.

그래서 legalDocument(오늘 생성)는 익명에 안 보임 → BE 가 **read 토큰으로 서버사이드** 읽어 공개 제공. consent `term`·siteSettings 와 같은 BE-read 패턴이지만, **legal 은 `apicdn` 익명이 아니라 `api.sanity.io`(origin) + `Authorization: Bearer {SANITY_TOKEN}`** 으로 읽는 게 차이(익명 거부 우회).

**Sanity 가 접근을 고치면**(또는 새 프로젝트로 이관) → FE-direct 로 되돌리고 이 프록시 제거 가능. 결정 히스토리: [docs/features/consent-and-terms.md](../../../../../../../docs/features/consent-and-terms.md), 메모리 `sanity_node22_manifest`(원인 규명 과정).

## 설정 / 운영

- `SANITY_TOKEN`(Viewer read-only) — prod/staging SSM(`/plop/<env>/SANITY_TOKEN`) + terraform `user_secret_names`. 로컬은 `.env.local`(없으면 legal 프록시 빈 응답 = 로컬에서 법적고지 안 뜸, 허용).
- 캐시 TTL `pungdong.sanity.legal-ttl-ms`(기본 5분). legal 은 거의 안 바뀌어 길어도 무방.

## 작업 전 / 계약

- 컨트롤러 시그니처/응답 바꾸면 **같은 PR 에서** [types.ts](../../../../../../../docs/api-clients/types.ts) `LegalDocument` 갱신.
- Sanity `legalDocument` 스키마는 [`sanity/schemas/legalDocument.ts`](../../../../../../../sanity/schemas/legalDocument.ts)(slug/body 집합 = FE 렌더러 계약). body 의 styles/lists/marks 는 FE `PortableTextBody` 와 1:1.

## 안전망 테스트

`src/test/.../usecase/LegalUseCaseTest` — 실 시큐리티 체인, `LegalDocumentClient` 만 `@MockBean`. `L1` 인증 없이 GET /legal/terms 200(permitAll) / `L2` 없음 404.
