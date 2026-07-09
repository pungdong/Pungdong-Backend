# CLAUDE.md — identity-verification (본인확인 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. 본인확인은 강사 신청 전용이 아니라 **계정 공유 자산** — 수강(강의 신청 전) / 강사(전환 시) 어느 플로우에서든 같은 레코드를 만들고 읽는다. (memory `identity-verification-model`)
> **방식 = 휴대폰 SMS(다날), 포트원 REST v2**. 간편인증(APP)은 CI 미반환 케이스가 있어 실서비스는 SMS. 간편인증 어휘(`IdentityProvider`)는 `method`(SMS|APP) 판별자로 공존(지우지 말 것).

## 무엇이 들어있나

SMS 2단계(발송 → 확인):
- **컨트롤러**: `IdentityVerificationController` — `POST /identity-verifications`(생성+발송), `POST /{id}/confirm`(OTP), `POST /{id}/resend`(재발송), `GET /me`(내 최신 VERIFIED)
- **서비스**: `IdentityVerificationService` — create/confirm/resend 오케스트레이션 + 영속화. **OTP 만료·시도초과(max 5) 정책을 서비스가 강제**(모든 구현 공통). confirm 소유권 가드 + 멱등(이미 VERIFIED).
- **본인확인 경계**: `IdentityVerifier`(interface: `send`/`confirm`/`resend`) + `StubIdentityVerifier`(기본, 매직 OTP `"000000"`) / `DisabledIdentityVerifier`(fail-closed) / `RealPortOneIdentityVerifier`(포트원 REST). `pungdong.identity-verification.mode` = `stub`(기본) / `disabled` / `real`. 경계는 외부 호출만, 영속화는 서비스(payment `TossPaymentClient` 결).
- **CI/DI 암호화**: `CryptoStringConverter`(AES-256/GCM, `IDENTITY_CRYPTO_KEY`). `ci`/`di` 에 `@Convert`. 읽는 소비자 없음(write-side 보호).
- **엔티티/enum**: `IdentityVerification`(account_id FK, `status`/`method`/`portoneVerificationId`/`carrier`(enum)/`otpExpiresAt`/`attemptCount`/CI·DI/verifiedAt), `IdentityVerificationStatus`(READY/VERIFIED/FAILED), `IdentityVerificationMethod`(SMS/APP), `Carrier`(SKT/KT/LGU/*_MVNO), `IdentityVerificationErrorCode`, `IdentityProvider`(APP 대비), `ForeignerType`.
- **레포**: `IdentityVerificationJpaRepo`(`findTopByAccountIdAndStatusOrderByIdDesc` = 최신 VERIFIED)
- **dto/**: `IdentityVerificationRequest`, `IdentityVerificationResult`(create/resend), `ConfirmIdentityVerificationRequest`/`ConfirmIdentityVerificationResult`, `MyIdentityVerificationResponse`

보안 매처(`/identity-verifications/**` → authenticated)는 `global/security/SecurityConfiguration`. `{id}` 는 소유권 검증(`requireMine`) 후 동작.

## 작업 전 반드시 읽기

- **[docs/features/identity-verification.md](../../../../../../../docs/features/identity-verification.md)** — 정책·왜·히스토리. **여기부터.**
- **[docs/architecture/identity-verification.md](../../../../../../../docs/architecture/identity-verification.md)** — 흐름/ER/에러코드/권한
- **memory `identity-verification-model`** — 수강/강사 시점에 수집, 가입엔 없음 · 방식 전환
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **계정 공유 자산으로 승격** (2026-06-09) — 처음(#34)엔 `instructorapplication` 하위. 본인확인은 수강에서도 쓰는 계정 자산이라 별도 도메인. 강사 신청은 `verificationId` 로 **참조만**(제출 시 `status==VERIFIED` 검증).
- **선행 게이트 = 공유 403 신호(-1017)** (2026-07-08) — "본인인증 선행 필요"를 두 소비자가 **같은 신호**로 낸다: `IdentityVerificationRequiredException` → **403 + code -1017**(`ErrorCode.IDENTITY_VERIFICATION_REQUIRED`). 소비자별 판정만 다름 — (1) **수강신청**(`EnrollmentService.submit`)은 요청에 verificationId 가 없어 **세션 계정으로 최신 VERIFIED 조회**(`GET /me` 와 동일 쿼리), (2) **강사 신청**(`resolveVerification`)은 `verificationId` 가 가리키는 레코드가 `status != VERIFIED` 일 때. ⚠️ 강사 신청의 **없는/남의 verificationId 는 -1017 아님 → 400 유지**(IDOR·잘못된 입력이지 "본인인증하러 가라"가 아님). FE 는 -1017 만 본인인증 화면으로 분기.
- **SMS 2단계로 승격** (2026-07-07, 이 PR) — SMS OTP 는 본질적으로 발송→확인 2단계라 경계를 넓히고 레코드를 **생성 시점(READY)에 영속화**. 이로써 "레코드 존재=verified" 불변식이 깨져 소비자에 `status==VERIFIED` 가드 추가.
- **무만료 유지** — `verified` = 최신 **VERIFIED** 존재. `verifiedAt` 노출만. `GET /me` 가 단일 진실원. 법적 주기 정해지면 TTL.
- **모드 게이트** — `real` 은 다날 CPID 개통 후. 그 전 로컬/테스트 `stub`, prod `disabled`.
- **OTP 만료 = 상대초로 내림 (2026-07-10)** — 발송/재발송 응답의 `otpExpiresInSeconds`(서버 계산 잔여 초)가 FE 카운트다운의 **단일 출처**. `otpExpiresAt`(LocalDateTime, 오프셋 없음)은 표시/디버그용. **왜**: 컨테이너 JVM=UTC 라 offset 없는 `otpExpiresAt` 을 FE(KST)가 9h 과거로 읽어 즉시 만료로 표시되던 prod 버그 → 근본봉합으로 `PungdongApplication.main` 에서 전역 `TimeZone.setDefault(Asia/Seoul)` 고정(모든 LocalDateTime 필드 정합) + 카운트다운은 시계/TZ 무관한 상대초로. TTL 은 stub 180s/real 300s(하드코딩 금지). 교훈 = memory `feedback_container_tz_localdatetime`.

## 안전망 테스트

`src/test/.../usecase/IdentityVerificationUseCaseTest`(S1 create·S2 confirm·S3 재사용·V1 오답·D1 resend·T1/T2 미인증·R1 비소유). skip + status 게이트는 `InstructorApplicationUseCaseTest` 가 create→confirm→submit 으로 검증. 실 PortOne 클라이언트는 라이브 미검증(CPID 개통 후 수동).
