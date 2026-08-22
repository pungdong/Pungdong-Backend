# 강사 자격 · 온보딩 (instructor onboarding)

> **피처 문서.** 수강생(STUDENT)이 종목별 강사(INSTRUCTOR)가 되는 전 과정의 **컨텍스트 · 정책 ·
> 결정 히스토리**를 한곳에 묶는다. 이 피처는 코드상 **3개 도메인**(discipline · identity-verification ·
> instructor-application)이 협력한다.
>
> **역할 분담 (drift 방지)**: 이 문서는 **"무엇을 / 왜"**(정책·결정)를 소유한다. **"어떻게"**(ER ·
> 엔드포인트 · 컴포넌트)는 아래 도메인 아키텍처 문서가 source of truth — 여기선 링크만 하고 복붙하지 않는다.

## 한 줄

종목 선택 → 본인확인 → **내 자격증 등록**(강사 레벨) → 제출(자격증 id 참조) → 어드민 검수 → 승인 시 **종목별** INSTRUCTOR 부여(STUDENT 유지) + 그 자격증 **VERIFIED = 공개 인증마크**.

## 협력 도메인 (구현 출처)

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| 종목 | [discipline.md](../architecture/discipline.md) | 종목 목록 · 자격증 필요 여부 |
| 본인확인 | [identity-verification.md](../architecture/identity-verification.md) · [features/identity-verification.md](identity-verification.md) | 계정 공유 본인확인(휴대폰 SMS, 다날/포트원) |
| 신청·심사 | [instructor-application.md](../architecture/instructor-application.md) | 신청/검수(Rule B 호출처) |
| 자격증·검증 상태 | [certificate.md](../architecture/certificate.md) | 자격증 정본 + `verification` + 검수 큐(Rule A/B/C 구현) |
| API 계약 | [types.ts](../api-clients/types.ts) | FE 단일 출처 |

---

## 정책 (requirements)

### 종목 (discipline)
- **종목 = BE `discipline` 테이블** (code · name · requiresCertification · active · sortOrder). **Sanity/enum 아님** — `requiresCertification`(자격증 필수 여부)이 BE 가 강사신청 때 강제하는 **비즈룰**이고, 강의/강사 필터·쿼리 대상이라서.
- **자격증 필요 여부**: 프리다이빙 · 스쿠버 = 필요, 수영 · 서핑 = 불필요.
- **출시 scope = 프리다이빙 · 스쿠버만.** 수영/서핑은 후속. `GET /disciplines` 는 `active` 만 반환하므로, 출시 전 수영/서핑은 **seed 제외 또는 `active=false`** 로 둔다. (현재 seeder 는 4종 seed — 출시 전 정리 필요.)
- **종목별 "단체 목록" 만 Sanity** 카탈로그(`disciplineCode` 키). 프리다이빙→AIDA/SSI/Molchanovs, 스쿠버→PADI/NAUI/CMAS, 수영/서핑→없음. ⚠️ **종목 자체는 BE, 단체만 Sanity** — 혼동 주의.
- **단체별 자격증(등급) 카탈로그도 Sanity** (`certOrganization.certifications[]`): 종목별 등급을 평탄화 레벨(LEVEL_1~4/INSTRUCTOR) + 단체 명칭(displayName)으로 정의. 본인 자격 레벨 선택(향후 `ratingCode` 로드맵)과 코스 작성 "단체→레벨" 이 같은 카탈로그를 읽는다. BE 는 `level` 만 enum(`course.CertLevel`)으로 안다.
- **종목 확장 (잦을 예정)**: "Sanity 에 추가" 가 아니라 **`discipline` 행 추가**다. 지금 = `DisciplineSeeder` 한 줄(코드+배포) 또는 SQL `INSERT`. 확장 빈도가 높아지면 **배포 없는 어드민 엔드포인트 `POST /admin/disciplines`** 로 (로드맵 — 미해결 섹션). 종목 아이콘/마케팅 카피 같은 순수 표현물이 필요하면 Sanity 로 `code` 키잉해 enrich 가능(코어는 BE).

### 본인확인 (identity verification)
> 정책·방식·히스토리는 **[features/identity-verification.md](identity-verification.md)** 가 소유 — 여기선 강사 온보딩 관점만.
- **계정 공유 자산** — 수강(강의 신청 전) / 강사(전환 시) 공유. 한 번 하면 **재사용(skip)** — 강사 신청 진입 시 `GET /identity-verifications/me` 로 확인. 제출은 `status==VERIFIED` verificationId 만 참조.
- **방식 = 휴대폰 SMS(다날)** — 간편인증 대신 CI/DI 안정 확보용. 실연동은 포트원 REST v2(2단계: 발송→OTP 확인). 무만료 유지.
- **실 다날 라이브는 CPID 개통(리드타임 최대 1주) 후** — 그 전 로컬/테스트는 stub(매직 OTP), prod 는 `mode=disabled` fail-closed.

### 강사 신청 (application)
- **종목별 1회** — `(account, discipline)` 유니크. 프리다이빙 + 스쿠버 동시 가능.
- 자격증 필요 종목: **강사 레벨 자격증 1건 이상**(내 자격증에 등록한 것, `certificateIds` 로 참조) 필수. 불필요 종목(수영/서핑): 생략 가능.
- **검수**: 어드민 승인/반려(사유). 승인 시 `INSTRUCTOR` 추가(STUDENT 유지) + `isCertified=true` + 첨부 자격증 `VERIFIED`. 권한은 매 요청 DB 재계산 → 재로그인 불필요.
- **재신청**: 반려 시 재제출(`PUT /me`). **승인된 종목 재신청 불가**(400).

### 검수를 기다리는 동안 — 준비는 허용, 판매는 승인 후 (2026-08-22)

**검수는 수동이라 하루쯤 걸린다.** 앱까지 온 신청자를 그동안 놀려 둘 수 없어서, 승인 전에도 **강의 등록 · 가용시간/일정 등록 · 커스텀 위치 · 프로필 준비**를 열어 둔다(게이트는 "승인" 이 아니라 **"그 종목 신청 보유"**). 앱에 온 시점에 할 수 있는 걸 최대한 해두게 하려는 제품 결정이다.

**다만 승인 전에는 정식 강사가 아니므로 그 강의가 학생에게 노출되면 안 된다.** 그래서 선은 **준비 ↔ 판매** 사이에 긋는다.

| 구간 | 필요 조건 |
|---|---|
| 강의 생성·수정, 가용시간·일정, 커스텀 위치, 프로필 | **그 종목 신청 보유** (상태 무관) |
| 강의 발행(OPEN 전환) | **그 종목 승인(APPROVED)** |
| 둘러보기 · 공개 상세 · 슬롯 피커 · 신청/결제 | **그 종목 승인(APPROVED)** |

**왜 조회에도 거나**: 발행 시점만 막으면 **열어 둔 뒤 반려된** 강사의 강의가 계속 팔린다 — 반려는 `CourseStatus` 를 건드리지 않아 그 강의는 여전히 OPEN 이다. 구현·읽기 경로 목록은 [architecture/course.md](../architecture/course.md).

**승인이 종목별인 게 여기서도 그대로다** — 프리다이빙 승인자는 스쿠버 강의를 발행할 수 없다. "강사임" 은 계정 속성이 아니라 종목별 속성이다.

⚠️ **데모(seeded) 코스도 예외가 아니다.** 데모 노출은 `SiteSettings.showSeededCourses` 라는 별개 축이 담당하고, 승인 규칙에는 시드 예외를 넣지 않았다 — prod 데모 코스는 `seeded` 표식이 누락된 이력이 있어 코드 예외가 정작 거기서 안 먹기 때문이다. **데모 강사는 어드민에서 실제로 승인**해 규칙을 그대로 통과시킨다.

### 자격증 (certificate) — 정본은 "내 자격증" (2026-08-22 수렴)
- **자격증은 한 곳에만 있다** — `StudentCertificate`(`/certificates`, 프로필 탭 &gt; 내 자격증). 강사 신청은 그 행의 **id 를 참조**하고(`certificateIds[]`), 심사 결과는 행의 `verification` 에 붙는다. 전엔 신청이 별도 `ApplicationCertificate`(단체+이미지)를 소유해 강사가 **같은 자격증을 두 번** 올렸고, "신청하면 자동 등록 + 인증마크"가 불가능했다. 옛 첨부는 V37 이 전 상태(APPROVED→VERIFIED / SUBMITTED→PENDING / REJECTED→REJECTED)로 백필한 뒤 drop — 번호·취득일은 옛 신청이 안 받아 **백필 행만 null**.
- **단체 단위** — 한 종목에 여러 단체(AIDA + PADI + Molchanovs). 단체는 신청이 아니라 자격증에 붙는다. `OTHER`(기타)는 새 필드 없이 `organizationName` 이 단체명(필수).
- **이미지는 비공개(개인정보)** — 자격증/보험 이미지는 어드민·본인만 봐야 한다. 비공개 S3 버킷(public ACL 없음)에 올리고, 저장값은 공개 URL 이 아니라 **객체 key**(자격증 `studentCertificate/{accountId}/…`, 보험·백필 자격증 `instructorCertificate/{accountId}/…` — 회원별 그룹핑으로 탈퇴 시 prefix 정리). 표시용 URL 은 어드민/본인 조회 때만 **presigned(TTL 3분)** 으로 발급. (왜 presigned: 비공개 버킷이라 추측 불가 + SigV4 서명 필요, 유출돼도 짧은 TTL 안에서만 유효.) 공개-의도 이미지(코스/커뮤니티)는 성격이 정반대(SEO·SSG 영구 공개 URL) → **별도 public 버킷**.
- **(선택) 다이빙보험** — 종목 신청별 **옵셔널** 이미지 첨부(`InstructorApplication.insuranceFileKey`, 업로드 경로 `POST /instructor-applications/certificate-images` 는 이제 **보험 전용**). **왜 계정 공유가 아니라 종목별인가**: 보험은 활동 특화(다이빙보험이 향후 서핑/카약을 커버 안 함) → 계정 단위 "보험 하나"는 종목 확장 시 의미상 오류. 다종목 신청자의 재첨부 편의는 **FE prefill** 로 풀고 **데이터는 신청별 진실**로 둠. (사용자 결정 2026-06-30.)
- ~~**자격증 관리 탭**(`POST /instructor-applications/certificates`, 검수 없이 append)~~ → **삭제**. 승인된 강사의 추가 자격증은 내 자격증에 등록하면 아래 Rule A 가 검수 큐에 넣는다.

### 자격증 검증 (verification) — 상태 규칙 3개 (단일 출처)

**검증마크 = `verification.status === 'VERIFIED'` 하나.** 공개 뱃지(브랜딩·강의상세 강사·프로필·강사 browse 칩/필터)의 파생원이 "승인 신청의 자격증" → "VERIFIED 자격증"으로 바뀌었다(형태는 v1 그대로). **`isInstructor`/roles 는 계속 APPROVED 신청 기준** — `requiresCertification:false` 종목(수영/서핑)은 자격증이 없어 "VERIFIED ≥ 1"로 강사를 정의할 수 없고, 회수는 어드민 판단.

상태: `NONE | PENDING | VERIFIED | REJECTED`, 경로(kind): `APPLICATION`(신청과 함께) · `ADDITIONAL`(승인 종목에 추가) · `RE_VERIFY`(VERIFIED 의 식별필드 수정). **강사 레벨** = `INSTRUCTOR | INSTRUCTOR_TRAINER`. 수강생 레벨은 검수 대상이 아니라 항상 NONE.

- **Rule A (자격증 쓰기)** — 강사 레벨 자격증의 생성·식별필드(종목/단체/레벨/번호/사진) 수정 시 그 종목 신청이 `APPROVED` 면 → `PENDING`(이전 NONE/REJECTED 면 ADDITIONAL, VERIFIED 면 RE_VERIFY — 마크가 즉시 빠지므로 FE 는 편집 전 확인), 아니면 → `NONE`. 기록 필드(취득일/발급기관/강의연결/표시명 스냅샷) 수정은 상태 불변. **예외**: 백필 행의 `certificateNumber` **null → 값** 채우기는 기록 보완(VERIFIED 유지). 심사 중(PENDING·APPLICATION)인 자격증의 번호·사진 정정은 상태 그대로(어드민이 현재 값을 본다).
- **Rule B (신청 이벤트)** — submit/resubmit → `certificateIds[]` 전부 `PENDING(APPLICATION)` + **그 종목의 NONE 강사레벨 자격증 자동 첨부**(BE 보정 — 어드민이 한 번에 다 보고 한 번에 승인; FE 가 "내 자격증에서 선택" 피커를 안 만들어도 된다), 재제출에서 빠진 건 `NONE`. approve → `VERIFIED`; reject → `REJECTED` + reason 복사. **승인 시 sweep**: 심사 중(SUBMITTED)에 새로 올린 같은 종목의 NONE 강사레벨 자격증은 `PENDING(ADDITIONAL)` 로(영원히 NONE 으로 남는 구멍 해소).
- **Rule C (가드, 400 + 사용자용 msg — FE 는 그대로 노출)** — `APPROVED` ∧ `requiresCertification` 종목에서 `{VERIFIED, PENDING}` 강사레벨 자격증이 **0 이 되는 쓰기**(삭제·강사레벨 미만 하향·종목 변경) 거부: "이 종목의 마지막 검증 자격증이에요. 다른 자격증을 먼저 등록해주세요." 심사 중(PENDING·APPLICATION) 자격증의 삭제("심사 중인 자격증은 삭제할 수 없어요.")·종목 변경·하향("심사 중인 자격증은 종목이나 자격 등급을 바꿀 수 없어요.")도 거부. 자격증 불필요 종목엔 적용 안 함(그 종목 강사는 자격증으로 정의되지 않는다 — 다만 강사레벨 자격증을 올리면 검수·마크는 허용).
- **인정한 구멍**: 마지막 VERIFIED 를 RE_VERIFY 로 올렸다가 반려되면 종목에 검증 자격증 0 + role 유지. 자동 회수 없음 — 어드민 목록 "검증 자격증 0건" 플래그(PR2), 사용자는 REJECTED+사유 보고 다시 수정하면 Rule A 로 PENDING.

**내 자격증에서 강사 레벨을 올렸을 때 (FE 표면)**

| 종목 신청 상태 | 동작 |
|---|---|
| APPROVED (이미 강사) | Rule A → 즉시 `PENDING(ADDITIONAL)` "검수중" 뱃지, 어드민 큐 ADDITIONAL 행 |
| 없음 / REJECTED | `NONE` + 카드 CTA "강사 신청하고 인증받기" → 신청 플로우에 `certId` prefill |
| SUBMITTED | `NONE`, CTA 없음("심사 중인 신청이 있어요 — 승인 후 검수돼요") → 승인 sweep 이 잡음 |

**어드민 큐 단위 = review item**(`certificate_review`: kind NEW/ADDITIONAL/RE_VERIFY, `GET /admin/certificate-reviews` · `/counts` · `/{reviewId}` · `POST /{reviewId}/approve|reject` 로 통일) — ADDITIONAL/RE_VERIFY 는 신청이 APPROVED 상태에서 생겨 SUBMITTED 행이 없기 때문. NEW 의 승인/반려는 신청 승인/반려에 위임(권한 부여 그대로). RE_VERIFY 행엔 `previous`(최초 VERIFIED 시점의 종목/단체/레벨/번호, 대기 중 또 고쳐도 갱신 안 함) — 자격증 행이 이미 덮인 뒤라 이 테이블이 유일한 보관처. 목록·상세의 `verifiedCertificateMissing` 이 "검증 자격증 0건"(승인 ∧ 필수 종목 ∧ 살아있는 검증 0) 을 표시한다 — 자동 회수 없음. 기존 `/admin/instructor-applications/**` 는 NEW 만 보는 보조 경로. **PR1(모델·규칙·백필·소비자 전환)과 PR2(검수 큐 API)는 통합 브랜치로 함께 배포** — PR1 만 나가면 ADDITIONAL/RE_VERIFY 가 PENDING 에서 빠져나올 어드민 경로가 없다.

- **확장 로드맵**: `level` 로 강의 생성 시 **레벨 게이트**(level2 강사가 level3 등록 불가) · 공개 뱃지에 `level`/`certificationDisplayName` 노출(v1.5) · ADDITIONAL/RE_VERIFY 결과 알림 딥링크(v1.5).

### 어드민
- **ADMIN 권한 = DB role**(`Account.roles`). "누구를 admin 으로"의 **목록만 env**(`ADMIN_EMAILS`) → 부팅 시 부여. Sanity 같은 CMS 에 두지 않음(보안 경계).
- **검수 페이지 = 검수 큐**(`/admin/certificate-reviews/**`): counts(세 종류 합산) · 목록(상태 필터/전체, 최신순, kind·단체 칩·`verifiedCertificateMissing`) · 상세(NEW: 본인확인 PII + 보험 + 첨부 자격증 풀 필드 / RE_VERIFY: 자격증 + `previous` 대조) · 승인/반려 by `reviewId`.

---

## 결정 히스토리 (timeline)

- **2026-08-22 — 준비/판매 분리**: 승인 전에도 강의·일정을 만들 수 있지만 **발행·노출·판매는 그 종목 승인 후**. 그전까지 게이트가 아예 없어 **강사 신청을 한 번도 안 한 계정도** 강의를 OPEN 해 둘러보기에 띄우고 결제를 받을 수 있었다(FE 가 홈 카피 "모두 강사 인증 완료" 를 검증하다 발견). 데모 코스는 코드 예외 대신 **데모 강사 승인**으로 통과시킨다.
| 시점 | 결정 | PR |
|---|---|---|
| 2026-06-08 | 레거시 `Account` 플래그 → **전용 `InstructorApplication` 엔티티 + 상태머신**. 본인확인 stub · 이미지저장 어댑터 경계 | #34 |
| 2026-06-08 | 레거시 `/sign/instructor/*` · `Account.organization` 등 **제거** (스택 PR retarget 사고 → 재-랜딩) | #35 / #36 |
| 2026-06-08 | 어드민 검수 보강(counts · 전체목록 · 처리이력) + **env allowlist 부트스트랩** | #37 |
| 2026-06-09 | 본인확인을 **계정 공유 도메인으로 승격** + `GET /me`(skip) | #38 |
| 2026-06-10 | **종목(discipline) 도입** + 강사신청 종목별 + **단체=자격증 단위(다중)** + 자격증 관리 탭 | #39 |
| 2026-06-29 | **자격증 이미지 비공개화** — staging/prod 업로드 실패 수정(public-read ACL ↔ Block Public Access + 컨테이너 작업디렉터리 temp 파일 쓰기). 비공개 업로드(객체 key) + 조회 시 presigned(TTL 3분) 서빙. 공개-의도(코스) 서빙은 별도 public 버킷 후속 | #138 |
| 2026-06-30 | **(선택) 다이빙보험 첨부** — 종목 신청별 nullable `insuranceFileKey`(자격증과 동일 비공개 패턴). 계정 공유 아님(보험=활동 특화, 종목 확장 대비) — 편의는 FE prefill. V6 마이그레이션 | #140 |
| 2026-08-22 | **강사 자격 검증 트랙 수렴** — 자격증 정본을 `StudentCertificate` 로 통일(신청은 `certificateIds` 참조, `ApplicationCertificate` 삭제·백필), `verification` 상태 + Rule A/B/C + 검수 큐 테이블, 공개 인증마크 = VERIFIED. FE 핸드오프(PungDong `docs/features/certificate.md`)를 BE 실태와 대조해 4건 보정(소비자 5곳·제출 시 자동 첨부·`previous`→테이블·전 상태 백필). PR1(모델·규칙) → PR2(검수 큐 API), 통합 브랜치 `feat/certificate-verification` | (통합 PR) |

(각 결정의 "왜"는 해당 도메인 `CLAUDE.md` 의 결정 히스토리 섹션에도 터스하게 기록됨.)

---

## 미해결 / 확장 (로드맵)

- 🔴 **실 본인확인기관 연동** — CI/DI 암호화 저장 + 비동기 푸시/재발송/검증 흐름. 사업자등록 + 기관 계약 후. (QA 에서 stub↔실연동 차이 이슈 다수 예상)
- 🟡 **자격 레벨 게이트** — 자격증 `level` 은 이미 있다(2026-08-22 수렴). 남은 건 강의 생성 시 **강사 레벨 ≥ 코스 목표 레벨** 게이트.
- 🟡 **검수 결과 알림(v1.5)** — ADDITIONAL/RE_VERIFY 승인·반려 푸시/인앱 + 딥링크. 공개 뱃지에 `level`/`certificationDisplayName` 노출도 v1.5.
- ~~🟡 **강의 ↔ 종목 연결**~~ — ✅ 해소(2026-08-15). `Lecture.classKind` 는 v1 삭제로 사라졌고, Course 는 처음부터 `disciplineCode` 를 쓴다.
- 🟡 **어드민 종목 관리 (배포 없는 확장)** — `POST/PUT /admin/disciplines` (추가 · active · 순서 · 이름). **종목 확장이 잦을 예정이라 우선순위 ↑.** 현재는 `DisciplineSeeder`(코드+배포)/SQL. 종목은 계속 BE 테이블(비즈룰·쿼리 유지), 관리 surface 만 추가 — Sanity 로 옮기는 게 아님.

---

## 관련 메모리

- `identity-verification-model` — 본인확인 시점/공유/무만료/stub 결정
- `project_simplification_plan` — 전체 로드맵 · 출시 일정
