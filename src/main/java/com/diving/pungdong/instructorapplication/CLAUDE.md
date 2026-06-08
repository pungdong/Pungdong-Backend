# CLAUDE.md — instructor-application (강사 신청 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 로 새로 만든 도메인. 한 폴더에 controller·service·repo·entity·dto 를 모은다 (account / notification 과 동일 구조). `Account` 를 단방향 참조 — account 는 이 패키지를 모른다.

## 무엇이 들어있나

수강생 → 강사 전환 신청의 제출/심사 전체:
- **컨트롤러**: `InstructorApplicationController`(신청자 `/instructor-applications/**`), `AdminInstructorApplicationController`(어드민 `/admin/instructor-applications/**`)
- **서비스**: `InstructorApplicationService`(상태머신 강제 — 모든 전이는 여기서)
- **본인확인 경계**: `IdentityVerifier`(interface) + `StubIdentityVerifier`(dev, 즉시 VERIFIED) / `DisabledIdentityVerifier`(prod fail-closed). `pungdong.identity-verification.mode` = `stub`(기본) / `disabled`
- **이미지 저장 경계** (`storage/`): `CertificateImageStorage`(interface) + `S3CertificateImageStorage`(prod) / `LocalCertificateImageStorage`(dev, 로컬 디스크 + `/local-uploads/**` 정적 서빙 `LocalUploadsWebConfig`). `pungdong.storage.s3.enabled` = `false`(기본) / `true`
- **엔티티**: `InstructorApplication`(계정당 1건, `account_id` UNIQUE), `ApplicationCertificate`(자격증 이미지 1:N), `IdentityVerification`(본인확인 결과), `InstructorApplicationStatus`/`IdentityProvider`(enum)
- **레포**: `InstructorApplicationJpaRepo`, `ApplicationCertificateJpaRepo`, `IdentityVerificationJpaRepo`
- **dto/**: identity / submit / 조회 / 어드민 DTO

보안 매처(`/admin/instructor-applications/**` → ADMIN, `/instructor-applications/**` → authenticated)는 **`global/security/SecurityConfiguration`** 에 있음 — 새 엔드포인트 추가 시 거기서 갱신.

## 작업 전 반드시 읽기

- **[docs/architecture/instructor-application.md](../../../../../../../docs/architecture/instructor-application.md)** — 흐름/모델/권한 매트릭스/설계 간극
- **memory `identity-verification-model`** — 본인확인(간편인증+CI)을 강사 전환 시점에 수집한다는 제품 결정. 본인확인 stub 의 근거.
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **전용 엔티티 + 상태머신** — 레거시 `Account.isRequestCertified/isCertified` 두 boolean 방식을 대체. 그 방식은 반려/심사이력 표현이 불가능하고, 승인 시 `isCertified` 를 안 켜는 버그가 있었다. (사용자 결정 2026-06-08)
- **본인확인 stub 경계** — 디자인은 간편인증을 전면에 두지만 실 본인확인기관 연동은 deferred. `IdentityVerifier` 구현 교체만으로 실연동 전환. `ci/di` 는 현재 mock 평문 → 실연동 시 **암호화 저장 필수**.
- **두 외부연동을 FcmGateway 패턴으로 게이트** (S3·본인확인) — `@ConditionalOnProperty` 로 정확히 하나만 활성(스캔 순서 무관). dev=stub/local 기본, prod=실연동/fail-closed. prod 는 `STORAGE_S3_ENABLED=true` + `IDENTITY_VERIFICATION_MODE=disabled`(실 구현 전) 로 override (application.yml 의 `pungdong.*` 블록 참고). 가짜 본인확인/가짜 업로드가 운영에 새지 않게.
- **organizationCode = 문자열 (enum 아님)** — 단체 목록(PADI/SSI/AIDA/.../OTHER)은 **Sanity 카탈로그**가 출처. BE enum 으로 박으면 단체 추가마다 배포 필요. trade-off: BE 는 code 를 검증하지 않고 신뢰(`OTHER` 직접입력 빈값만 체크).
- **2-phase 업로드** — 자격증 이미지는 `POST /certificate-images`(multipart)로 먼저 올려 URL 을 받고, 제출 JSON 이 그 URL 을 참조. 제출 컨트랙트가 깔끔한 JSON 이 됨.
- **승인 = additive role** — STUDENT 유지 + INSTRUCTOR 추가. 권한은 매 요청 DB 재계산이라 토큰 재발급 불필요 (use-case `R3`).
- **중복/없음 응답** — 중복 신청은 400(레포에 409 인프라 없음, `EmailDuplicationException` 처럼 400 통일). 미신청 내 신청 조회는 200 `{status:NONE}`(404 아님 — repo API 규칙).

## 안전망 테스트

`src/test/.../usecase/InstructorApplicationUseCaseTest` — 실제 H2 + 시큐리티 체인, S3 만 `@MockBean`, 본인확인은 stub 그대로. 신청/승인/반려/재제출/권한을 건드리면 여기가 회귀를 잡는다. (S/V/D/R/J/A/U 시리즈)

## 아직 안 한 것 (후속 PR)

- 레거시 `/sign/instructor/*` + `account.InstructorCertificate` + `findAllRequestInstructor` 제거 (이관 완료 후)
- REST Docs `document(...)` 컨트롤러 테스트 + `api.adoc` include (현재 use-case 로만 검증)
- 실 본인확인기관 연동 (CI/DI 암호화 + 비동기 푸시 흐름)
