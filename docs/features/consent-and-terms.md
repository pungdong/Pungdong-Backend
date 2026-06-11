# 동의 / 약관 (consent-and-terms)

> **피처 문서** — 정책·왜·히스토리를 소유한다. 구현(ER·엔드포인트·필드)은 [docs/architecture/consent.md](../architecture/consent.md) 로 링크만, 복붙하지 않는다.

## 한 줄

사용자가 약관에 **동의한 이력**을 법적 증빙 가능한 형태로 남긴다. 약관 **콘텐츠**(전문/요약/버전)는 Sanity 가 소유하고 FE 가 화면별로 직접 읽어 보여준다. BE 는 **"누가 / 어떤 약관 버전에 / 어느 화면에서 동의했나"** 를 기록하고, 동의 시점의 전문을 불변 박제해 분쟁에 대비한다.

## 협력 도메인 / 시스템

| 구성요소 | 위치 | 역할 |
|---|---|---|
| consent 도메인 | [architecture/consent.md](../architecture/consent.md) | 동의 기록 · 약관 버전 박제(증빙) · 조회 |
| Sanity `term` | FE 레포 `sanity/schemas/term.ts` | 약관 콘텐츠(전문/요약/`key`/`version`/`contexts`) authoring·저장 |
| FE | apps/web · mobile | 화면(context)별 약관을 Sanity 에서 읽어 표시 + 동의 `(key,version)` 을 BE 로 전송 |
| identity-verification / instructor-application | 각 도메인 문서 | 동의를 수집하는 화면들. 진행 게이트 boolean 과 consent 이력이 공존 |

## 정책 (requirements)

### 저장소 분담 — 콘텐츠는 Sanity, 동의는 BE
- 약관 **콘텐츠**(전문·요약·버전·노출화면)는 **Sanity 가 단일 출처**. 비개발자가 편집, 재배포 불필요.
- **동의 이력**은 법적/트랜잭션 데이터 → **BE DB**. FK 무결성·불변성 필요.
- FE 는 약관 전문을 **Sanity 에서** 읽는다. BE 엔 `(key, version)` 만 보낸다 — 본문을 BE 로 보내지 않음(위변조 방지). 박제할 전문은 **BE 가 Sanity 에서 직접** 받는다.

### 박제(snapshot) + 참조 — 유저별 전문 복사 금지
- `(key, version)` 당 **1행만** `AgreementTermArchive` 에 박제. 동의 이력 `Consent` 는 그 행을 **참조(FK)**.
- 유저 N명이 같은 버전에 동의해도 전문은 1번만 저장(효율) + 박제 행은 **불변**(증빙).
- 분쟁 시: `consent → archive.body` 로 그 사용자가 본 정확한 전문을 꺼낸다.

### 버전 관리
- 약관 **의미가 바뀌는 수정 = Sanity 에서 `version` bump** (운영 규약). 오타 등 사소한 수정은 그대로.
- 개정 = 새 `version` = 새 박제 행. 기존 동의 이력은 옛 버전을 가리킨 채 보존(그 사용자가 동의한 건 옛 버전).
- **Sanity revision 이력에 의존하지 않는다** — retention 한계로 장기 법적 증빙 부적합. 그래서 BE 에 박제.

### 동의 화면(context)
- `context` = 동의를 수집한 화면. Sanity `term.contexts` 와 **같은 어휘** (`signup` / `identity_verification` / `instructor_application` / `payment`).
- 한 약관이 여러 화면에 노출 가능(Sanity `contexts` 배열). FE 는 화면 기준으로 약관을 쿼리해 표시.

### 식별자
- `key` = 논리적 약관 1개당 고유(버전 무관 동일). `(key, version)` 쌍이 "정확히 어떤 약관의 어떤 버전" 을 지목.

### 버전 권위는 BE (다운그레이드 위조 차단)
- 동의에 기록할 **version 은 BE 가 정한다** — `key` 로 Sanity 의 **현재 활성 버전**을 조회해 그 값으로 박제·기록.
- 클라이언트가 보낸 `version` 은 "화면에서 본 버전" 으로 보고 **현재와 일치하는지만** 검증 → 다르면 400(옛/위조 버전, 또는 세션 중 개정 → 재확인 유도).
- 이유: 클라이언트 version 을 그대로 믿으면, 옛 버전이 이미 박제돼 있을 때 그 옛 버전명을 보내 **더 약한 옛 약관에 동의한 것으로 다운그레이드**할 수 있다. version 출처를 서버로 고정해 막는다.

## 결정 히스토리

| 시점 | 결정 | 비고 |
|---|---|---|
| 2026-06-12 | 약관을 단일 `term` 타입(Sanity)으로 통합, `contexts` 로 화면 스코프 | 목적별 타입 분리 대신 한 타입 + 태그. FE 가 화면 기준 쿼리 |
| 2026-06-12 | 동의 증빙은 **동의 시점에 박제**(live CMS 의존 X) | "그 순간 본 전문" 이 법적 기준 |
| 2026-06-12 | 유저별 전문 복사 거부 → **버전당 1행 박제 + id 참조** | 정규화·효율 |
| 2026-06-12 | 저장소: **콘텐츠=Sanity(편집 UI) + 동의/박제=BE DB** 하이브리드 | BE 전부 끌어오면 약관 수정마다 admin/재배포 필요 → Sanity 가 그 역할 |
| 2026-06-12 | **첫-동의 lazy freeze** (웹훅 사전 freeze 아님) | MVP — 웹훅 셋업 불필요 |
| 2026-06-12 | **버전 권위 = BE** (key 로 현재 버전 조회, 클라이언트 version 은 일치검증만) | 다운그레이드 위조 차단. 사용자 지적으로 초기 short-circuit 허점 발견·수정 |
| 2026-06-12 | 기존 `agreedRequiredTerms` boolean 게이트와 **공존** | consent 는 그 위의 감사 이력. 수렴은 후속 |

## 미해결 / 확장

- 🟡 **개정 시 재동의 유도** — 필수 약관 version 상승 시 "active version vs 동의한 version" 비교로 재동의 요구. 비교 정책/엔드포인트 미정.
- 🟡 **boolean 게이트 수렴** — `agreedRequiredTerms` 를 consent 로 대체/수렴할지. 현재 공존(FE 가 동의 시점에 `POST /consents` 별도 호출).
- 🟡 **사전 freeze(웹훅)** — Sanity publish 시 미리 박제하면 첫-동의 경합·런타임 Sanity 의존 제거. 현재 lazy.
- 🟢 **버전 bump 자동 강제** — Sanity 에서 의미 변경 시 version bump 를 강제하는 장치 없음(운영 규율). 필요 시 Sanity 워크플로/검증 추가.
- 🟢 **결제(payment) context** — enum/어휘엔 있으나 결제 피처 자체가 미구현.

## 관련 메모리

- `identity-verification-model` — 본인확인 수집 시점(수강/강사). 동의도 같은 화면들에서 수집.
- `architecture_package_by_feature` — consent 도 feature 패키지로 추가.
