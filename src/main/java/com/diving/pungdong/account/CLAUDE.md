# CLAUDE.md — account (인증 / 계정 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> 이 도메인은 **domain-based(package-by-feature)** 로 정착된 첫 두 도메인 중 하나 (다른 하나는 [notification](../notification/CLAUDE.md)). 레거시는 아직 layered (`controller/ service/ ...`) — 신규/재작성 도메인은 이 패키지처럼 한 폴더에 controller·service·repo·entity·dto 를 모은다.

## 무엇이 들어있나

로그인 / 회원가입 / 토큰 / 계정·강사·프로필 관련 전부:
- **컨트롤러**: `SignController`(가입·로그인·refresh·logout·중복체크), `DeviceController`(`/me/devices` — FCM 토큰 등록/해제, 푸시), `EmailController`(인증코드 발송/검증), `AccountController`, `ProfilePhotoController`
  - ⚠️ **강사 신청/전환은 이 도메인 아님** — 별도 [instructorapplication](../../instructorapplication/CLAUDE.md) 도메인. 레거시 `/sign/instructor/*` + `Account.organization/income/isRequestCertified` 는 제거됨. `InstructorCertificate`(엔티티/서비스/`/account/instructor/certificate/list` 읽기)만 잔존 — 강사 프로필 기능 나오면 정리.
- **서비스**: `AccountService`, `FirebaseTokenService`, `ProfilePhotoService`, `EmailService`, `InstructorCertificateService`
- **부트스트랩**: `AdminAccountInitializer`(ApplicationRunner) — env `pungdong.admin.emails` allowlist 의 계정에 부팅 시 `ROLE_ADMIN` 부여(idempotent). 권한은 DB role 이 출처, 목록만 env. (어드민 심사 페이지용)
- **레포**: `AccountJpaRepo`, `FirebaseTokenJpaRepo`, `InstructorCertificateJpaRepo`, `ProfilePhotoJpaRepo`
- **엔티티**: `Account`, `Role`, `Gender`, `AuthProvider`, `DeviceType`, `FirebaseToken`, `ProfilePhoto`, `InstructorCertificate`, `InstructorImgCategory`
- **dto/**: `dto/signUp`, `dto/signIn`, `dto/auth`(AuthToken·RefreshRequest), `dto/instructor`, `dto/emailCheck`, `dto/emailCode`, `dto/restore`, `dto/update`, ...

보안 인프라(`JwtTokenProvider`, `JwtAuthenticationFilter`, `SecurityConfiguration`, `@CurrentUser`, `UserAccount`)는 **이 패키지가 아니라 `global/security/`** — 인증은 전 도메인을 가로지르는 인프라라 공유. URL→권한 매처 추가도 거기서.

## 작업 전 반드시 읽기

- **[docs/architecture/sign-up.md](../../../../../../../docs/architecture/sign-up.md)** — 흐름(가입/로그인/refresh)·데이터 모델·권한 매트릭스
- **memory `identity-verification-model`** — 가입 무인증 + 본인확인(간편인증+CI) 시점 결정
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나 — git diff 가 못 담는 것)

- **가입 시 별도 인증 없음** (설계 결정). 본인확인(간편인증 + CI 수집)은 수강생=강의신청 전, 강사=강사전환 시점. `EmailController`의 `/email/code/*` 는 가입용 아님 — **비밀번호 재설정 + 계정복구** 전용 (`AccountService.modifyForgetPassword`, `updateAccountDeleted`). 지우지 말 것. (memory: identity-verification-model)
- **PR #19**: 이메일 가입/로그인 e2e 완성 — 가입 시 tokens 동봉(auto-login, 별도 login 불필요), refresh 토큰 회전, 로그아웃 블랙리스트(Redis), JSON 401/403, CORS.
- **PR #17**: 가입 페이로드 슬림화 + `Account` 에 OAuth 식별 필드(`AuthProvider`) 추가.
- **토큰 정책** (TTL/rotation/무효화): AT 1시간, RT **30일**(rotation 과 결합한 슬라이딩 윈도우 = 최대 비활성 허용 기간), refresh 시 **옛 RT 즉시 무효화**(재사용 replay 차단), 로그아웃 시 AT·RT 블랙리스트(TTL=유효기간 일치). 상세·이유 → [docs/architecture/sign-up.md](../../../../../../../docs/architecture/sign-up.md) "토큰 정책" 섹션. 값의 원천 = `JwtTokenProvider` 상수.
- **OAuth(Kakao/Naver)**: 출시 후로 deferred. 블로커 = 사업자등록증(~2026-06-04) → 개발자 앱 등록. (memory: project_simplification_plan)
- **닉네임 = 형식 가드 + 예약어 차단** (2026-08-19). 단일 출처 `global/validation/NickNamePolicy`. 닉네임은 표시명이 아니라 **공개 URL 식별자**(`/instructors/{nickName}`)라 좁게 간다 — 2~16자 한글(완성형)·영문·숫자·밑줄만, 그리고 예약어(브랜드 `풍덩`/`pungdong`/`plop`·한글 역할어는 **포함**, 라틴 역할어는 **정확일치·접두**, 라우트어 `public`·`suggested` 등은 정확일치) 차단. 판정 전 정규화(대소문자·구분자 제거·리트 치환)라 `p_u_n_g_d0ng` 도 걸린다.
  - **형식은 DTO `@Pattern`, 예약어는 서비스 가드**(`AccountService.checkReservedNickName`) — 어드민(`ROLE_ADMIN`)은 예약어 예외라 principal 을 아는 곳에서만 판정할 수 있다. 막아 둔 목적이 *우리가 나중에 쓰려고* 라 정작 우리가 못 쓰면 안 된다.
  - **세 경로가 같은 규칙**: 가입 · `PATCH /account/nickName` · `GET /sign/check/nickName`. 중복확인은 throw 하지 않고 `{exists, available, reason}` 을 200 으로 준다 — 형식 위반·예약어는 아무도 안 써서 `exists:false` 라 `available` 없이는 FE 가 초록불을 켠다.
  - 새 리터럴 경로를 `/instructors/*` 네임스페이스에 추가하면 **그 단어를 `RESERVED_EXACT` 에 넣을 것** — 안 넣으면 그 닉네임 계정의 프로필이 영영 안 열린다.
  - 기존 계정은 재검증하지 않는다(가입·변경 시점에만). 이미 예약어를 가진 계정은 `account/audit` 리포트로 개별 안내. 정책·왜·히스토리 → [docs/features/nickname.md](../../../../../../../docs/features/nickname.md), 구현 규칙표 → [docs/architecture/sign-up.md](../../../../../../../docs/architecture/sign-up.md) "닉네임 정책".
- **회원탈퇴 = soft delete → 유예 30일 → PII 익명화** (2026-06-29, 앱스토어 계정삭제 의무). `DELETE /account`(본문 없음 — 세션이 본인 증명, 비번 재확인 안 받음 + 현재 access token 블랙리스트; 구버전 앱의 비번 동봉은 무시=하위호환, FE 이슈 #462), 복구 `PATCH /account/deleted-state`(유예 내·이메일 인증), 경과 후 `AccountAnonymizationService`/`AccountDeletionScheduler`(`@Profile("!test")`)가 PII 파기. 결제기록 법정보존(전자상거래법)이라 row 는 하드삭제 못 하고 PII 만 익명화·멱등. 정책·보존표·법적근거·스토어 요건 = [docs/features/account-deletion.md](../../../../../../../docs/features/account-deletion.md). `Account.deletedAt`/`anonymizedAt` + 마이그레이션 `V4`.

## 안전망 테스트

`src/test/.../account/` + `src/test/.../usecase/AuthUseCaseTest`, `SignUpUseCaseTest`, `NickNamePolicyUseCaseTest`(닉네임 형식·예약어·어드민 예외·오탐 없음). 인증 흐름 건드리면 여기가 회귀를 잡는다. `AuthUseCaseTest` 의 L1/L2 = 로그아웃 블랙리스트, F1~F5 = refresh + rotation 재사용 차단 검증 (블랙리스트·rotation 모두 배선 완료, phase_0_deferred #1/#2 resolved).
