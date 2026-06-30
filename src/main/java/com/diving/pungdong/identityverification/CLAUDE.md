# CLAUDE.md — identity-verification (본인확인 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. 본인확인(간편인증)은 강사 신청 전용이 아니라 **계정 공유 자산** — 수강(강의 신청 전) / 강사(전환 시) 어느 플로우에서든 같은 레코드를 만들고 읽는다. (memory `identity-verification-model`)

## 무엇이 들어있나

- **컨트롤러**: `IdentityVerificationController` — `POST /identity-verifications`(생성), `GET /identity-verifications/me`(내 최신 상태)
- **서비스**: `IdentityVerificationService` — verify(경계 위임) + getMyVerification(최신 1건)
- **본인확인 경계**: `IdentityVerifier`(interface) + `StubIdentityVerifier`(dev, 즉시 VERIFIED) / `DisabledIdentityVerifier`(prod fail-closed). `pungdong.identity-verification.mode` = `stub`(기본) / `disabled`
- **엔티티/enum**: `IdentityVerification`(account_id FK, CI/DI/carrier/foreignerType/provider/verifiedAt), `IdentityProvider`(KAKAO/NAVER/TOSS/PASS/KB/PAYCO), `ForeignerType`(DOMESTIC/FOREIGN). `carrier`(통신사)·`foreignerType`(내외국인)은 요청 입력이 아니라 본인확인기관 반환 속성(CI/DI 와 동일) — verifier 가 채움. 처리방침 수집 항목과 1:1.
- **레포**: `IdentityVerificationJpaRepo`(`findTopByAccountIdOrderByIdDesc` = 최신 1건)
- **dto/**: `IdentityVerificationRequest`, `IdentityVerificationResult`, `MyIdentityVerificationResponse`

보안 매처(`/identity-verifications/**` → authenticated)는 `global/security/SecurityConfiguration`.

## 작업 전 반드시 읽기

- **[docs/architecture/identity-verification.md](../../../../../../../docs/architecture/identity-verification.md)**
- **memory `identity-verification-model`** — 수강/강사 시점에 본인확인 수집, 가입엔 없음
- 컨트롤러 시그니처/응답 바꾸면 **같은 PR 에서 [types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **계정 공유 자산으로 승격** (2026-06-09) — 처음(#34)엔 `instructorapplication` 하위에 있었으나, 본인확인은 수강에서도 쓰는 계정 자산이라 별도 도메인으로 분리. 강사 신청은 `verificationId` 로 **참조만**. FE 가 `GET /me` 로 skip(재인증 생략) 구현.
- **verificationId 도메인 간 재사용** — 강사 신청 제출은 "그 verification 이 이 계정 소유 + verified" 만 검증(목적 무관). 수강 때 받은 id 를 그대로 제출에 넣어도 통과.
- **GET /me = 최신 1건** — 계정당 여러 본인확인 레코드 허용(이력/감사), 조회는 최신(id desc). 미인증도 200 `{verified:false}`(404 아님).
- **무만료 (v1)** — `verified` = 레코드 존재. `verifiedAt` 은 노출만, 만료 판단 안 함. 법적 재인증 주기 정해지면 그 위에 TTL. stub 단계라 TTL 무의미.
- **stub 경계** — `ci/di` 는 mock 평문. 실 본인확인기관 연동 시 (a) `IdentityVerifier` 실 구현 + `mode=real`, (b) CI/DI 암호화, (c) 비동기 푸시 흐름.

## 안전망 테스트

`src/test/.../usecase/IdentityVerificationUseCaseTest`(I1 조회 / I2 미인증 / I3 최신). skip(재사용)은 `InstructorApplicationUseCaseTest` 가 verify→submit 으로 검증.
