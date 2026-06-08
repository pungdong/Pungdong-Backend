# 강사 신청 (instructor-application)

## 한 줄 요약

수강생(STUDENT)이 **본인확인 → 자격증 이미지 업로드 → 단체 선택 후 제출**하면 `InstructorApplication` 1건이 SUBMITTED 로 생기고, 어드민이 **승인/반려**한다. 승인 시 해당 계정에 `INSTRUCTOR` 역할이 **추가**(STUDENT 유지)되고 `isCertified=true` 가 된다.

레거시 `Account.isRequestCertified/isCertified` 플래그 + `/sign/instructor/*` 흐름을 대체하는 **신규 feature 패키지**(`instructorapplication/`). 한 계정당 신청 1건(`account_id` 유니크), 상태머신으로 제출/승인/반려/재제출을 표현한다.

> **본인확인은 stub.** 디자인(강사신청 화면)은 간편인증(카카오/네이버/토스/PASS/KB/페이코 → CI/DI)을 전면에 두지만, 실제 본인확인기관 외부 연동은 deferred다. 현재는 `StubIdentityVerifier` 가 즉시 VERIFIED 처리한다 (memory: `identity-verification-model`).

---

## 컴포넌트 지도

```mermaid
flowchart TB
    subgraph Apply["instructor-application 도메인 (feature 패키지)"]
        UserCtl["InstructorApplicationController<br/>/instructor-applications/**"]
        AdminCtl["AdminInstructorApplicationController<br/>/admin/instructor-applications/**"]
        Svc["InstructorApplicationService<br/>(상태머신 강제)"]
        Storage["CertificateImageStorage (interface)"]
        StoreS3["S3CertificateImageStorage (prod)"]
        StoreLocal["LocalCertificateImageStorage (dev)"]
        AppEntity["InstructorApplication"]
        CertEntity["ApplicationCertificate"]
        AppRepo["InstructorApplicationJpaRepo"]
        CertRepo["ApplicationCertificateJpaRepo"]
    end

    subgraph IDV["identity-verification 도메인 (별도 — 계정 공유 자산)"]
        IdvApi["POST /identity-verifications<br/>GET /identity-verifications/me"]
        IdvRepo["IdentityVerificationJpaRepo"]
        IdvEntity["IdentityVerification"]
    end

    subgraph Shared["크로스 도메인 (단방향 의존)"]
        Account["account.Account / Role / AccountJpaRepo"]
        S3up["service.image.S3Uploader → AWS S3"]
    end

    Sanity["Sanity 카탈로그<br/>(단체 목록 · 폼 안내문구)<br/>FE 가 직접 fetch"]

    UserCtl --> Svc
    AdminCtl --> Svc
    Svc --> Storage
    Storage --> StoreS3 --> S3up
    Storage --> StoreLocal
    Svc --> AppRepo --> AppEntity
    Svc --> CertRepo --> CertEntity
    Svc -->|제출 시 verificationId 검증| IdvRepo --> IdvEntity
    AppEntity -.참조.-> IdvEntity
    AppEntity --> CertEntity
    Svc -->|승인 시 role 추가| Account
    Sanity -.organizationCode 선택지 제공.-> UserCtl
```

의존 방향은 한쪽 — `instructorapplication` 이 `account` 와 `identity-verification` 을 참조하고 그 역은 없다. 본인확인은 [identity-verification](identity-verification.md) 도메인(계정 공유 자산) 소관 — 신청은 `verificationId` 로 참조만. 단체 카탈로그는 Sanity 가 source of truth라 BE 는 `organizationCode` 문자열만 저장(enum 아님).

**이미지 저장 경계 (FcmGateway 패턴).** 자격증 이미지 저장은 인터페이스 + `@ConditionalOnProperty` 로 환경별 교체:

| 경계 | dev 기본 | prod | 프로퍼티 |
|---|---|---|---|
| 이미지 저장 | `LocalCertificateImageStorage` (로컬 디스크 + `/local-uploads/**` 서빙) | `S3CertificateImageStorage` | `pungdong.storage.s3.enabled` = `false` / `true` |

(본인확인 stub/disabled 경계는 identity-verification 도메인으로 이동.) → FE 는 AWS·본인확인기관 없이도 dev 에서 전체 흐름 검증 가능.

---

## 흐름 1 — 신청 제출 (2-phase, happy path)

```mermaid
sequenceDiagram
    participant FE
    participant IDV as identity-verification 도메인
    participant UC as InstructorApplicationController
    participant SVC as InstructorApplicationService
    participant S3
    participant DB

    Note over FE: (단체 목록/약관문구는 Sanity 에서 직접 fetch)
    FE->>IDV: GET /identity-verifications/me (skip 판단)
    alt 미인증
        FE->>IDV: POST /identity-verifications {realName,birth,phone,provider,agree}
        IDV-->>FE: 201 {verificationId, verified:true}
    end

    loop 자격증 1장씩
        FE->>UC: POST /certificate-images (multipart image)
        UC->>SVC: uploadCertificateImage
        SVC->>S3: upload → URL
        SVC-->>FE: {fileURL}
    end

    FE->>UC: POST /instructor-applications {verificationId, organizationCode, certificateImageUrls[]}
    UC->>SVC: submit
    SVC->>SVC: 검증(소유 verification · 기타 단체 직접입력 · 중복 신청)
    SVC->>DB: insert instructor_application(SUBMITTED) + application_certificate[]
    SVC-->>FE: 201 {applicationId, status:SUBMITTED}
```

## 흐름 2 — 어드민 승인 / 반려

```mermaid
sequenceDiagram
    participant Admin
    participant AC as AdminInstructorApplicationController
    participant SVC as InstructorApplicationService
    participant DB

    Admin->>AC: GET /admin/instructor-applications?status=SUBMITTED
    AC-->>Admin: PagedModel<Summary> (대기 건만)
    Admin->>AC: GET /admin/instructor-applications/{id}
    AC-->>Admin: Detail (본인확인 PII 포함)

    alt 승인
        Admin->>AC: POST /{id}/approve
        AC->>SVC: approve(id, reviewer)
        SVC->>DB: application.status=APPROVED, reviewer/reviewedAt
        SVC->>DB: account.roles += INSTRUCTOR, isCertified=true
        Note over SVC: 권한은 매 요청 DB 에서 재계산 → 토큰 재발급 불필요
    else 반려
        Admin->>AC: POST /{id}/reject {reason}
        AC->>SVC: reject(id, reviewer, reason)
        SVC->>DB: application.status=REJECTED, rejectionReason
        Note over Admin: 신청자는 PUT /me 로 수정·재제출 (REJECTED→SUBMITTED)
    end
```

---

## 데이터 모델

```mermaid
erDiagram
    Account ||--o| InstructorApplication : "신청 (account_id UNIQUE)"
    Account ||--o{ IdentityVerification : "본인확인 이력"
    InstructorApplication }o--|| IdentityVerification : "참조한 본인확인"
    InstructorApplication ||--o{ ApplicationCertificate : "자격증 이미지"
    Account ||--o| InstructorApplication : "reviewer (어드민)"

    InstructorApplication {
        Long id PK
        Long account_id FK "UNIQUE"
        String status "SUBMITTED|APPROVED|REJECTED"
        String organizationCode "Sanity code, enum 아님"
        String organizationOther "OTHER 일 때만"
        Long identity_verification_id FK
        Long reviewer_id FK "nullable"
        String rejectionReason
        LocalDateTime submittedAt
        LocalDateTime reviewedAt
    }
    ApplicationCertificate {
        Long id PK
        Long application_id FK
        String fileURL "S3"
        int sortOrder
    }
    IdentityVerification {
        Long id PK
        Long account_id FK
        String realName "PII"
        String birth
        Gender gender
        String phoneNumber "PII"
        String ci "고유식별정보 (stub=mock)"
        String di
        IdentityProvider provider
        LocalDateTime verifiedAt
    }
```

설계 의도:
- **`organizationCode` 는 문자열** — 단체 추가가 Sanity 편집만으로 되도록(배포 불필요). 레거시 `Account.organization`(Organization enum, ordinal 저장 버그)은 이 도메인에서 쓰지 않는다.
- **자격증은 신청에 종속** — 재제출 시 신청과 함께 교체되는 스냅샷. 레거시 `account.InstructorCertificate`(Account 소유, 메타 없음)와 별개.
- **본인확인은 별도 엔티티** — 신청과 분리해 두면 재제출 시 새 본인확인을 참조할 수 있고, 실연동 전환 시 적재 경로만 바뀐다.

---

## 보안 / 권한 매트릭스

| 엔드포인트 | 메서드 | 권한 | 비고 |
|---|---|---|---|
| `/instructor-applications/me` | GET | 인증 | 본인 신청만. 미신청 시 200 `{status:NONE}` |
| `/instructor-applications/certificate-images` | POST | 인증 | multipart (`image`) |
| `/instructor-applications` | POST | 인증 | 제출. 중복/이미강사 → 400 |
| `/instructor-applications/me` | PUT | 인증 | 수정·재제출 (APPROVED 는 거부) |
| `/admin/instructor-applications` | GET | **ADMIN** | `?status=` 생략 시 전체, 지정 시 탭별. 기본 정렬 submittedAt desc |
| `/admin/instructor-applications/counts` | GET | **ADMIN** | 탭 뱃지용 `{submitted, approved, rejected, total}` |
| `/admin/instructor-applications/{id}` | GET | **ADMIN** | PII 포함 상세 (+ reviewerNickName / createdAt) |
| `/admin/instructor-applications/{id}/approve` | POST | **ADMIN** | INSTRUCTOR 부여 + isCertified |
| `/admin/instructor-applications/{id}/reject` | POST | **ADMIN** | 사유 필수 |

매처는 `SecurityConfiguration`: `/admin/instructor-applications/**` → `hasRole(ADMIN)`, `/instructor-applications/**` → `authenticated`. 승인 후 역할 변경은 매 요청 DB 재계산이라 **재로그인 불필요** (use-case `R3` 가 검증).

### 어드민 지정 (authorization = DB role)

admin 권한은 **DB role 이 source of truth** (`Account.roles` 에 `ADMIN`) — Sanity 같은 CMS 에 두지 않는다(보안 경계). "누구를 admin 으로"의 **목록만 env allowlist** 로 관리: `pungdong.admin.emails`(env `ADMIN_EMAILS`, 콤마구분). 부팅 시 `account.AdminAccountInitializer`(ApplicationRunner)가 그 이메일 계정에 `ROLE_ADMIN` 보장(idempotent). admin 은 **일반 가입 후** 목록에 있으면 승격 — 가입 전이면 다음 기동 시 부여. (use-case `B1`)

---

## 알려진 설계 간극

- 🔴 **본인확인 미연동 (stub)** — dev 는 `StubIdentityVerifier`(즉시 VERIFIED, mock 평문 CI/DI), prod 는 `mode=disabled` 로 `DisabledIdentityVerifier`(fail-closed)로 막힌다. 실 연동 시 (a) `IdentityVerifier` 실 구현 + `mode=real`, (b) CI/DI **암호화 저장**, (c) 푸시 대기/비동기 검증 흐름(디자인 ③④⑤ 화면)을 반영. 출시 전 본인확인이 필수면 이 실 구현이 블로커.
- 🔴 **S3 미연동** — Phase 4 버킷 provision 전까지 prod 의 `S3CertificateImageStorage` 는 못 쓴다. dev 는 `LocalCertificateImageStorage`(로컬 디스크)로 대체. 버킷 생기면 `pungdong.storage.s3.enabled=true` 만 켜면 됨.
- 🟢 **레거시 신청/전환 흐름 제거 완료** — `/sign/instructor/*` 4종 + `/account/instructor`·`/account/instructor-application` + `Account.organization/income/isRequestCertified` + `findAllRequestInstructor` 제거됨. 잔존: `InstructorCertificate`(엔티티/서비스/`/account/instructor/certificate/list` 읽기) + `Account.selfIntroduction`(강의 상세에서 읽힘) — 강사 프로필(instructor-profile) 기능 때 정리.
- 🟡 **REST Docs 스니펫 부재** — 이번 엔드포인트들은 use-case 테스트로만 검증되고 `document(...)` 컨트롤러 테스트가 없다. `api.adoc` 에 include 를 추가하지 않으므로 빌드는 깨지지 않지만, 공개 문서엔 아직 안 나온다. 후속 PR 에서 보강.
- 🟡 **organizationCode 미검증** — BE 는 Sanity 카탈로그와 대조하지 않고 문자열을 그대로 신뢰한다(`OTHER` 직접입력만 빈값 체크). 잘못된 code 가 들어와도 막지 않음 — Sanity 가 출처라는 결정의 trade-off.
- 🟢 **상태 단순화** — `UNDER_REVIEW` 없이 SUBMITTED 가 "검토 중"을 겸한다. 심사 담당자 분리/SLA 가 필요해지면 중간 상태 추가.

---

## 더 깊게: use-case 테스트로 보기

실제 동작의 단일 출처는 **[`usecase/InstructorApplicationUseCaseTest`](../../src/test/java/com/diving/pungdong/usecase/InstructorApplicationUseCaseTest.java)** (실제 H2 + 시큐리티 체인 + 실 서비스/JPA, S3 만 `@MockBean`, 본인확인은 stub 그대로). `@DisplayName` 을 위→아래로 읽으면 사양:

- `S1` 본인확인→제출 시 201 + SUBMITTED 1건 생성 · `S2` 미신청 시 `{status:NONE}` · `S3` 제출 내용이 내 신청 조회에 반영
- `V1` 본인확인 없이 제출 400 · `V2` 자격증 0장 400 · `V3` 기타 단체 직접입력 누락 400
- `D1` 심사 중 중복 제출 400
- `R1` 학생이 어드민 API 403 · `R2` 승인 시 INSTRUCTOR 추가 + isCertified=true · `R3` **승인 직후 옛 토큰으로 강사 전용 API 통과**
- `J1` 반려 시 사유 저장 · `J2` 반려 후 PUT 재제출 → SUBMITTED 복귀
- `A1` 어드민 대기 목록엔 SUBMITTED 만 (승인된 건 제외)
- `U1` 자격증 이미지 업로드 → S3 URL 반환
