# CLAUDE.md — account (인증 / 계정 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> 이 도메인은 **domain-based(package-by-feature)** 로 정착된 첫 두 도메인 중 하나 (다른 하나는 [notification](../notification/CLAUDE.md)). 레거시는 아직 layered (`controller/ service/ ...`) — 신규/재작성 도메인은 이 패키지처럼 한 폴더에 controller·service·repo·entity·dto 를 모은다.

## 무엇이 들어있나

로그인 / 회원가입 / 토큰 / 계정·강사·프로필 관련 전부:
- **컨트롤러**: `SignController`(가입·로그인·refresh·logout·중복체크·강사신청·firebase-token), `EmailController`(인증코드 발송/검증), `AccountController`, `ProfilePhotoController`
- **서비스**: `AccountService`, `FirebaseTokenService`, `ProfilePhotoService`, `EmailService`, `InstructorCertificateService`
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

## 안전망 테스트

`src/test/.../account/` + `src/test/.../usecase/AuthUseCaseTest`, `SignUpUseCaseTest`. 인증 흐름 건드리면 여기가 회귀를 잡는다. `AuthUseCaseTest` 의 L1/L2 = 로그아웃 블랙리스트, F1~F5 = refresh + rotation 재사용 차단 검증 (블랙리스트·rotation 모두 배선 완료, phase_0_deferred #1/#2 resolved).
