# CLAUDE.md — consent (동의/약관 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. 동의는 **계정 공유 자산** — 회원가입/본인확인/강사신청/결제 어느 화면이든 사용자가 약관에 동의하면 여기로 기록한다. `Account` 단방향 참조.

## 핵심 설계 — 박제(snapshot) + 참조

약관 **콘텐츠(전문/요약/버전)는 Sanity 가 소유**. BE 는 **"누가 어떤 약관 버전에 동의했나"**만 기록한다. 그런데 분쟁 시 "그 사용자가 본 정확한 전문"을 증빙해야 하므로:

- 어떤 `(key, version)` 에 **처음** 동의가 들어오면 → BE 가 Sanity 에서 전문을 받아 `AgreementTermArchive` 에 **불변 박제(버전당 1행)**.
- 이후 같은 버전 동의는 모두 그 박제 행을 **FK 참조** → 유저 N명이 동의해도 전문은 1번만 저장(효율) + 그 행은 불변(증빙).
- 약관 개정 = Sanity 에서 `version` bump = 새 박제 행. 기존 동의 이력은 옛 버전을 가리킨 채 보존.

**왜 BE 가 직접 Sanity 에서 받나** — FE 가 보낸 본문은 위변조 가능. 증빙이므로 FE 는 약관 **`key` 만** 보내고, 전문·version 은 권위 소스(Sanity)에서 BE 가 직접 가져온다.

**버전도 BE 가 전적으로 정한다 (위조 차단 + 오해 방지)** — 요청 계약에 version 이 **없다**. BE 는 `key` 로 Sanity 의 **현재 활성 버전**을 조회해 그 값으로 박제·기록한다(`fetchCurrentTerm(key)` — version 파라미터 없음이 핵심). FE 가 version 을 보내면 ① "FE 가 버전을 정한다" 는 오해, ② 옛 버전 다운그레이드 여지 → 둘 다 차단. 기록된 version 은 응답으로 알려준다.

## 무엇이 들어있나

- **컨트롤러**: `ConsentController` — `POST /consents`(동의 기록, 201), `GET /consents/me`(내 동의 이력)
- **서비스**: `ConsentService` — record(박제 없으면 freeze 후 동의 insert) / getMyConsents
- **약관 콘텐츠 경계**: `SanityTermClient`(interface, `FetchedTerm` 레코드 포함) + `HttpSanityTermClient`(JDK `HttpClient` 로 Sanity GROQ 조회). 테스트는 이 인터페이스를 `@MockBean`.
- **엔티티**: `AgreementTermArchive`(`(term_key, version)` UNIQUE, append-only 박제), `Consent`(account_id + agreement_term FK + context + agreedAt)
- **enum**: `ConsentContext`(SIGNUP/IDENTITY_VERIFICATION/INSTRUCTOR_APPLICATION/PAYMENT — DB=enum명, JSON=lowercase snake, Sanity `term.contexts` 와 동일 어휘)
- **레포**: `AgreementTermArchiveJpaRepo`(`findByTermKeyAndVersion`), `ConsentJpaRepo`(`findByAccountIdOrderByIdDesc`)
- **dto/**: `RecordConsentRequest`{context, **keys[]**}, `AgreementRef`{key, version}(**응답 전용** — 기록된 버전 보고), `RecordConsentResponse`{recorded, agreements[]}, `MyConsentResponse`(@Relation `consents`)

보안 매처(`/consents/**` → authenticated)는 `global/security/SecurityConfiguration`. Sanity 설정은 `application.yml` 의 `pungdong.sanity.*`(projectId/dataset/api-version, 기본값 박혀 있어 env 없이도 동작).

## 작업 전 반드시 읽기

- **[docs/features/consent-and-terms.md](../../../../../../../docs/features/consent-and-terms.md)** — 정책·왜·히스토리 (교차 도메인 피처 문서)
- **[docs/architecture/consent.md](../../../../../../../docs/architecture/consent.md)** — 흐름/모델/권한
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**
- 약관 스키마(Sanity `term`)는 **이 레포** [`sanity/schemas/term.ts`](../../../../../../../sanity/schemas/term.ts) (Studio = 어드민 CMS, BE 레포 소유) — `key`/`version`/`contexts` 가 이 도메인 계약. FE 는 projectId + GROQ 만 복사해 읽음.

## 결정 히스토리 (왜 이렇게 됐나)

- **하이브리드: Sanity authoring + BE 박제** (2026-06-12) — 약관 전문을 전부 BE DB 로 끌어오면 single-source 지만 약관 수정마다 재배포/admin UI 필요. Sanity 를 편집 UI 로 쓰고(version bump), BE 는 동의 시점에 그 버전을 박제 → 편집 편함 + 증빙 둘 다. (사용자 결정)
- **유저별 전문 복사 금지** — 동의기록에 전문을 박으면 유저×약관 폭증. 버전당 1행 박제 + consent 는 id 참조로 정규화.
- **버전 권위 = BE, 요청은 key-only** (2026-06-12) — 초기 구현은 `findByTermKeyAndVersion(key, 클라이언트version)` short-circuit → 옛 v1 박제가 남아 있으면 클라이언트가 `v1` 을 보내 **다운그레이드 기록** 가능(허점). 1차 수정은 `fetchCurrentTerm(key)` + 클라이언트 version 일치검증이었으나, FE 가 version 을 보내는 것 자체가 "FE 가 버전을 정한다" 는 오해를 부른다는 지적으로 **요청에서 version 제거**(key 만). BE 가 현재 버전 조회·기록. (사용자 지적 2회)
- **첫-동의 freeze 트리거** — Sanity publish 웹훅 대신 "처음 동의 시 BE 가 fetch+freeze". 웹훅 셋업 불필요(MVP). 동시 첫-동의 경합은 UNIQUE 제약이 막고 500 → FE 재시도로 성공(허용). 박제 재사용과 무관하게 현재 버전 조회는 매 동의마다 수행(권위 확보).
- **boolean 게이트와 공존** — 기존 본인확인/강사신청의 `agreedRequiredTerms` boolean(진행 게이트)은 유지. consent 는 그 위의 **감사 이력**. FE 가 동의 시점에 `POST /consents` 를 별도 호출.

## 안전망 테스트

`src/test/.../usecase/ConsentUseCaseTest` — 실제 H2 + 시큐리티, `SanityTermClient` 만 `@MockBean`. `C1` 첫 동의 박제(+응답 version) / `C2` 같은 버전 재사용 / `C3` 개정(v1→v2) 별도 박제 / `V1` 활성 약관 없음 400 / `V2` 빈 keys 400 / `C4` GET /me 이력.

## 아직 안 한 것 (후속)

- 약관 개정 시 **재동의 유도** (active version vs 동의한 version 비교) — FE 정책 + 엔드포인트 미정
- Sanity publish **웹훅 기반 사전 freeze** (현재는 첫-동의 lazy freeze)
- 기존 `agreedRequiredTerms` boolean 을 consent 로 **수렴/대체**할지 (현재 공존)
- REST Docs `document(...)` (현재 use-case 로만 검증)
