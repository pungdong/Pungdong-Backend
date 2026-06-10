# CLAUDE.md — instructor-application (강사 신청 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 로 새로 만든 도메인. 한 폴더에 controller·service·repo·entity·dto 를 모은다 (account / notification 과 동일 구조). `Account` 를 단방향 참조 — account 는 이 패키지를 모른다.

## 무엇이 들어있나

수강생 → 강사 전환 신청의 제출/심사 전체:
- **컨트롤러**: `InstructorApplicationController`(신청자 `/instructor-applications/**`), `AdminInstructorApplicationController`(어드민 `/admin/instructor-applications/**`)
- **서비스**: `InstructorApplicationService`(상태머신 강제 — 모든 전이는 여기서)
- **본인확인**: 이 도메인 아님 — 별도 [identity-verification](../identityverification/CLAUDE.md) 도메인. 신청은 `verificationId` 로 **참조만**(제출 시 소유+verified 검증). 진입 skip 은 FE 가 `GET /identity-verifications/me`.
- **이미지 저장 경계** (`storage/`): `CertificateImageStorage`(interface) + `S3CertificateImageStorage`(prod) / `LocalCertificateImageStorage`(dev, 로컬 디스크 + `/local-uploads/**` 정적 서빙 `LocalUploadsWebConfig`). `pungdong.storage.s3.enabled` = `false`(기본) / `true`
- **종목**: 이 도메인 아님 — [discipline](../discipline/CLAUDE.md). 신청은 `disciplineCode` 로 참조, 제출 시 `DisciplineService.getActiveByCode` 로 검증 + `requiresCertification` 으로 자격증 필수 여부 분기.
- **엔티티**: `InstructorApplication`(**종목별** 1건, `(account_id, discipline_code)` UNIQUE), `ApplicationCertificate`(자격증 = **단체+이미지**, 1:N — 한 종목에 여러 단체), `InstructorApplicationStatus`(enum). `IdentityVerification` 참조(identity-verification 도메인 소유)
- **레포**: `InstructorApplicationJpaRepo`, `ApplicationCertificateJpaRepo` (+ identity-verification 의 `IdentityVerificationJpaRepo` 로 제출 시 검증)
- **dto/**: submit / 조회 / 어드민 DTO (identity DTO 는 identity-verification 도메인)

보안 매처(`/admin/instructor-applications/**` → ADMIN, `/instructor-applications/**` → authenticated)는 **`global/security/SecurityConfiguration`** 에 있음 — 새 엔드포인트 추가 시 거기서 갱신.

## 작업 전 반드시 읽기

- **[docs/architecture/instructor-application.md](../../../../../../../docs/architecture/instructor-application.md)** — 흐름/모델/권한 매트릭스/설계 간극
- **memory `identity-verification-model`** — 본인확인(간편인증+CI)을 강사 전환 시점에 수집한다는 제품 결정. 본인확인 stub 의 근거.
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **전용 엔티티 + 상태머신** — 레거시 `Account.isRequestCertified/isCertified` 두 boolean 방식을 대체. 그 방식은 반려/심사이력 표현이 불가능하고, 승인 시 `isCertified` 를 안 켜는 버그가 있었다. (사용자 결정 2026-06-08)
- **본인확인을 계정 공유 자산으로 승격** (2026-06-09) — 처음(#34)엔 이 도메인 하위(`POST /instructor-applications/identity-verification`)였으나, 본인확인은 수강에서도 쓰는 계정 자산이라 별도 [identity-verification](../identityverification/CLAUDE.md) 도메인으로 분리. FE 가 `GET /me` 로 skip(재인증 생략) 구현. stub/disabled 경계도 그 도메인으로 이동.
- **이미지 저장을 FcmGateway 패턴으로 게이트** (S3) — `@ConditionalOnProperty` 로 dev=local / prod=S3. prod 는 `STORAGE_S3_ENABLED=true` 로 override. (본인확인 게이트 `IDENTITY_VERIFICATION_MODE` 는 identity-verification 도메인 소관.)
- **종목별 신청 + 단체=자격증 단위** (2026-06-10) — 신청은 종목별(`(account_id, discipline_code)` UNIQUE, 프리다이빙+스쿠버 동시 가능). 단체(organizationCode)는 신청이 아니라 **`ApplicationCertificate` 단위** — 한 종목에 여러 단체 자격(AIDA+PADI+Molchanovs) 가능. organizationCode 는 문자열(Sanity 카탈로그, 종목별; BE 검증 안 함, `OTHER` 빈값만 체크). 종목의 `requiresCertification` 으로 자격증 필수 여부 분기(수영/서핑=불필요).
- **자격증 관리 (MVP)** — 승인된 강사는 같은 종목 재신청 차단(400), 대신 `POST /instructor-applications/certificates` 로 자격증만 **검수 없이 append**. 향후 "인증 요청하기"→검수→자격 승격/레벨(자격증에 `ratingCode` 추가)로 확장. (use-case `DS5`)
- **2-phase 업로드** — 자격증 이미지는 `POST /certificate-images`(multipart)로 먼저 올려 URL 을 받고, 제출 JSON 이 그 URL 을 참조. 제출 컨트랙트가 깔끔한 JSON 이 됨.
- **승인 = additive role** — STUDENT 유지 + INSTRUCTOR 추가. 권한은 매 요청 DB 재계산이라 토큰 재발급 불필요 (use-case `R3`).
- **중복/없음 응답** — 종목별 중복 신청은 400(레포에 409 인프라 없음, `EmailDuplicationException` 처럼 400 통일). 내 신청 조회 `GET /me` 는 종목별 **목록**(CollectionModel) — 미신청 종목은 항목 없음(404 아님, FE 가 선택 종목으로 필터).
- **어드민 지정 = DB role + env allowlist** (Sanity 아님) — admin 권한은 `Account.roles` 의 `ADMIN`(authz 는 우리 신뢰경계). "누구를 admin 으로"의 목록만 env `pungdong.admin.emails` → `account.AdminAccountInitializer` 가 부팅 시 부여. 어드민 심사 목록은 counts(탭 뱃지) + status 옵셔널(전체 탭) 지원. (사용자 결정 2026-06-09)

## 안전망 테스트

`src/test/.../usecase/InstructorApplicationUseCaseTest` — 실제 H2 + 시큐리티 체인, S3 만 `@MockBean`, 본인확인은 stub 그대로. 신청/승인/반려/재제출/권한을 건드리면 여기가 회귀를 잡는다. (S/V/D/R/J/A/U 시리즈)

## 아직 안 한 것 (후속 PR)

- 레거시 신청/전환 흐름(`/sign/instructor/*` + `Account.organization/income/isRequestCertified` + `findAllRequestInstructor`)은 **제거 완료**. 남은 `InstructorCertificate`(엔티티/서비스/`/account/instructor/certificate/list` 읽기 + `Account.selfIntroduction`)는 강사 프로필(instructor-profile) 기능 때 정리.
- REST Docs `document(...)` 컨트롤러 테스트 + `api.adoc` include (현재 use-case 로만 검증)
- 실 본인확인기관 연동 (CI/DI 암호화 + 비동기 푸시 흐름)
