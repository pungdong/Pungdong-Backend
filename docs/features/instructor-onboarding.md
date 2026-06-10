# 강사 자격 · 온보딩 (instructor onboarding)

> **피처 문서.** 수강생(STUDENT)이 종목별 강사(INSTRUCTOR)가 되는 전 과정의 **컨텍스트 · 정책 ·
> 결정 히스토리**를 한곳에 묶는다. 이 피처는 코드상 **3개 도메인**(discipline · identity-verification ·
> instructor-application)이 협력한다.
>
> **역할 분담 (drift 방지)**: 이 문서는 **"무엇을 / 왜"**(정책·결정)를 소유한다. **"어떻게"**(ER ·
> 엔드포인트 · 컴포넌트)는 아래 도메인 아키텍처 문서가 source of truth — 여기선 링크만 하고 복붙하지 않는다.

## 한 줄

종목 선택 → 본인확인 → 자격증(단체+이미지) 등록 → 제출 → 어드민 검수 → 승인 시 **종목별** INSTRUCTOR 부여(STUDENT 유지).

## 협력 도메인 (구현 출처)

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| 종목 | [discipline.md](../architecture/discipline.md) | 종목 목록 · 자격증 필요 여부 |
| 본인확인 | [identity-verification.md](../architecture/identity-verification.md) | 계정 공유 본인확인(간편인증) |
| 신청·심사·자격증 | [instructor-application.md](../architecture/instructor-application.md) | 신청/검수/자격증 관리 |
| API 계약 | [types.ts](../api-clients/types.ts) | FE 단일 출처 |

---

## 정책 (requirements)

### 종목 (discipline)
- **종목 = BE `discipline` 테이블** (code · name · requiresCertification · active · sortOrder). **Sanity/enum 아님** — `requiresCertification`(자격증 필수 여부)이 BE 가 강사신청 때 강제하는 **비즈룰**이고, 강의/강사 필터·쿼리 대상이라서.
- **자격증 필요 여부**: 프리다이빙 · 스쿠버 = 필요, 수영 · 서핑 = 불필요.
- **출시 scope = 프리다이빙 · 스쿠버만.** 수영/서핑은 후속. `GET /disciplines` 는 `active` 만 반환하므로, 출시 전 수영/서핑은 **seed 제외 또는 `active=false`** 로 둔다. (현재 seeder 는 4종 seed — 출시 전 정리 필요.)
- **종목별 "단체 목록" 만 Sanity** 카탈로그(`disciplineCode` 키). 프리다이빙→AIDA/SSI/Molchanovs, 스쿠버→PADI/NAUI/CMAS, 수영/서핑→없음. ⚠️ **종목 자체는 BE, 단체만 Sanity** — 혼동 주의.
- **종목 확장 (잦을 예정)**: "Sanity 에 추가" 가 아니라 **`discipline` 행 추가**다. 지금 = `DisciplineSeeder` 한 줄(코드+배포) 또는 SQL `INSERT`. 확장 빈도가 높아지면 **배포 없는 어드민 엔드포인트 `POST /admin/disciplines`** 로 (로드맵 — 미해결 섹션). 종목 아이콘/마케팅 카피 같은 순수 표현물이 필요하면 Sanity 로 `code` 키잉해 enrich 가능(코어는 BE).

### 본인확인 (identity verification)
- **계정 공유 자산** — 수강(강의 신청 전) / 강사(전환 시) 공유. 한 번 하면 **재사용(skip)** — 강사 신청 진입 시 `GET /identity-verifications/me` 로 확인.
- **무만료 (v1)** — verified면 계속 유효. 법적 재인증 주기 정해지면 `verifiedAt` 위에 TTL.
- **실 본인확인기관 연동 deferred** — 현재 stub(즉시 통과). 간편인증 ②~⑤(인증서 선택·푸시 대기·**재발송**·검증 중)는 실연동 PR 때. prod 는 `mode=disabled` fail-closed.

### 강사 신청 (application)
- **종목별 1회** — `(account, discipline)` 유니크. 프리다이빙 + 스쿠버 동시 가능.
- 자격증 필요 종목: **자격증 1건 이상**(단체+이미지) 필수. 불필요 종목(수영/서핑): 생략 가능.
- **검수**: 어드민 승인/반려(사유). 승인 시 `INSTRUCTOR` 추가(STUDENT 유지) + `isCertified=true`. 권한은 매 요청 DB 재계산 → 재로그인 불필요.
- **재신청**: 반려 시 재제출(`PUT /me`). **승인된 종목 재신청 불가**(400).

### 자격증 (certificate)
- **단체 단위** — 한 종목에 여러 단체(AIDA + PADI + Molchanovs). 단체는 신청이 아니라 자격증에 붙는다.
- **자격증 관리 탭**: 승인된 강사가 자격증 추가(`POST /instructor-applications/certificates`). **MVP = 검수 없이 append**.
- **확장 로드맵**: 추가 시 "인증 요청하기" → 검수 → 자격 승격/레벨. 자격증에 `ratingCode`(예 "AIDA L2") + 강의 생성 시 **레벨 게이트**(level2 강사가 level3 등록 불가). 지금 구조(문자열 code + 자격증 N건)가 additive 수용.

### 어드민
- **ADMIN 권한 = DB role**(`Account.roles`). "누구를 admin 으로"의 **목록만 env**(`ADMIN_EMAILS`) → 부팅 시 부여. Sanity 같은 CMS 에 두지 않음(보안 경계).
- **검수 페이지**: counts(탭 뱃지) · 목록(상태 필터/전체, 최신순) · 상세(본인확인 PII + 자격증) · 승인/반려.

---

## 결정 히스토리 (timeline)

| 시점 | 결정 | PR |
|---|---|---|
| 2026-06-08 | 레거시 `Account` 플래그 → **전용 `InstructorApplication` 엔티티 + 상태머신**. 본인확인 stub · 이미지저장 어댑터 경계 | #34 |
| 2026-06-08 | 레거시 `/sign/instructor/*` · `Account.organization` 등 **제거** (스택 PR retarget 사고 → 재-랜딩) | #35 / #36 |
| 2026-06-08 | 어드민 검수 보강(counts · 전체목록 · 처리이력) + **env allowlist 부트스트랩** | #37 |
| 2026-06-09 | 본인확인을 **계정 공유 도메인으로 승격** + `GET /me`(skip) | #38 |
| 2026-06-10 | **종목(discipline) 도입** + 강사신청 종목별 + **단체=자격증 단위(다중)** + 자격증 관리 탭 | #39 |

(각 결정의 "왜"는 해당 도메인 `CLAUDE.md` 의 결정 히스토리 섹션에도 터스하게 기록됨.)

---

## 미해결 / 확장 (로드맵)

- 🔴 **실 본인확인기관 연동** — CI/DI 암호화 저장 + 비동기 푸시/재발송/검증 흐름. 사업자등록 + 기관 계약 후. (QA 에서 stub↔실연동 차이 이슈 다수 예상)
- 🟡 **자격 레벨/등급** — 자격증 `ratingCode` + 강의 생성 레벨 게이트. lecture 재설계와 함께.
- 🟡 **강의 ↔ 종목 연결** — 현재 `Lecture.classKind`(느슨한 string) → `disciplineCode` 정리. lecture 재설계 때.
- 🟡 **어드민 종목 관리 (배포 없는 확장)** — `POST/PUT /admin/disciplines` (추가 · active · 순서 · 이름). **종목 확장이 잦을 예정이라 우선순위 ↑.** 현재는 `DisciplineSeeder`(코드+배포)/SQL. 종목은 계속 BE 테이블(비즈룰·쿼리 유지), 관리 surface 만 추가 — Sanity 로 옮기는 게 아님.

---

## 관련 메모리

- `identity-verification-model` — 본인확인 시점/공유/무만료/stub 결정
- `project_simplification_plan` — 전체 로드맵 · 출시 일정
