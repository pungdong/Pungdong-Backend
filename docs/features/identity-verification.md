# 휴대폰 본인인증 (identity-verification)

> 피처 문서 — **정책·왜·히스토리**를 소유한다. 흐름·ER·엔드포인트 등 *어떻게*는 [docs/architecture/identity-verification.md](../architecture/identity-verification.md) 링크로 두고 복붙하지 않는다.

## 한 줄

사용자의 **CI/DI(고유식별정보)** 를 안정적으로 확보하기 위한 본인확인. 방식은 **휴대폰 SMS 본인인증(다날)**, 연동은 **포트원 REST v2 만으로**(별도 인증창/SDK 없이) 진행한다. 서버가 채널키·시크릿·PII 를 쥐고, FE 는 자체 UI 로 전화번호·OTP 화면을 만든다. 계정 공유 자산 — 수강(강의 신청 전)·강사(전환 시) 어느 플로우든 한 번 인증하면 재사용(skip)한다.

## 협력 도메인

| 영역 | 구현 문서 | 역할 |
|---|---|---|
| 본인확인 본체 | [architecture/identity-verification.md](../architecture/identity-verification.md) | 2단계(발송/확인) 흐름·상태·에러코드·CI/DI 암호화·권한 |
| 소비자(강사) | [architecture/instructor-application.md](../architecture/instructor-application.md) · [features/instructor-onboarding.md](instructor-onboarding.md) | 제출 시 `status==VERIFIED` verificationId 참조 |
| 동의/약관 | [architecture/consent.md](../architecture/consent.md) · [features/consent-and-terms.md](consent-and-terms.md) | `context='identity_verification'` 동의 감사 이력 |
| 약관 콘텐츠 | Sanity `term`(`sanity/schemas/term.ts`) | 필수 약관 문안 게재(FE 표시) |

## 정책 (requirements)

### 방식 — 왜 휴대폰(SMS)인가
- **간편인증(카카오/네이버/토스)은 CI 미반환 케이스가 있다.** CI/DI 를 안정적으로 주는 **휴대폰 본인인증(다날)** 으로 실 서비스 방식을 확정(2026-07-07). 간편인증(APP) 코드/어휘(`IdentityProvider`)는 **지우지 않고** `method`(SMS|APP) 판별자로 공존 — 향후 APP 방식이 붙을 수 있음.
- **API 방식 채택 근거**: 포트원 기술지원 회신 — "다날은 별도 인증창(SDK UI) 없이 REST API 호출만으로 본인인증 진행 가능". FE 상태머신이 단순해지고 시크릿·PII 가 서버에 갇힌다.

### 흐름·계약 (요지 — 상세는 도메인 문서)
- **create+send 결합** — `POST /identity-verifications` 한 번에 레코드 생성 + 문자 발송(FE 상태머신 단순화). 응답 `status:READY`.
- **status** `READY | VERIFIED | FAILED`. confirm 응답은 이번 시도 결과(VERIFIED|FAILED)로 좁혀 내려간다.
- **재사용/skip** — `GET /identity-verifications/me` 가 최신 **VERIFIED** 를 반환. 강사 신청·결제는 그 `verificationId` 재사용.
- **소유권** — `{id}` 는 클라 제공이나 confirm/resend 는 본인 것만(비소유=400 존재 숨김).

### 재사용 만료 — 무만료 (확정)
- `verified` = **최신 VERIFIED 레코드 존재**. `verifiedAt` 노출만, 만료 판단 안 함. `GET /me` 가 단일 진실원. (사용자 확정 2026-07-07) — 법적 재인증 주기가 정해지면 그 위에 TTL.
- OTP 자체의 **유효기한(`otpExpiresAt`)** 은 별개(짧음) — 재사용 만료가 아니라 그 OTP 세션 만료.

### CI/DI 보호
- CI/DI 는 고유식별정보 → **암호화 저장**(AES-256/GCM, `IDENTITY_CRYPTO_KEY`). 평문 금지.
- 현재 CI/DI 를 **읽는 소비자는 없음**(저장·보호가 목적). DI 기반 중복가입 확인은 미구현(확장 후보).

### 동의 기록 — 둘 다 (확정)
- create 요청의 `agreedRequiredTerms:true` **불린 게이트**(진행 차단) + FE 가 `POST /consents {context:'identity_verification', keys}` 로 **버전드 감사 이력** 적재. 두 계층 공존(consent 도메인 기존 설계와 동일).
- SMS API 방식의 필수 약관 = **"개인정보 수집·이용 및 제3자(다날) 제공 동의" 1건**. 문안은 Sanity `term`(context=`identity_verification`)에 게재(FE 표시).

### 모드 게이트
- `IDENTITY_VERIFICATION_MODE` = `stub`(로컬/테스트, 문자 미발송·매직 OTP `"000000"`) / `disabled`(prod, CPID 개통 전 fail-closed) / `real`(포트원/다날).

## 결정 히스토리

| 시점 | 결정 | 근거/PR |
|---|---|---|
| 2026-06-03 | 가입엔 본인확인 없음, 수강/강사 시점에 수집 | memory `identity-verification-model` |
| 2026-06-09 | 본인확인을 **계정 공유 도메인으로 승격** + `GET /me`(skip) | #38 |
| 2026-06-12 | 간편인증→휴대폰 전환 가능성 예고(CI 안정 확보) | memory |
| 2026-07-07 | **휴대폰 SMS(다날) + 포트원 REST v2 실연동**. status 다단계·carrier 입력·method 판별자·CI/DI 암호화. 무만료 유지·동의 둘 다 확정 | 이 PR |

## 미해결 / 확장

- 🔴 **실 다날 라이브 검증** — **CPID 개통(통신사 심사, 리드타임 최대 1주) 후**에만 실호출 검증 가능. 개통 전까지 `RealPortOneIdentityVerifier` 는 REST 명세 기반(미검증). 개통 후 OTP 에러코드·응답 필드 경로 보정 + 내외국인 판별.
- 🟡 **수강 플로우 미구현** — 강의 신청 전 본인확인 소비자는 아직 없음(도메인은 공유 자산으로 준비됨).
- 🟢 **DI 중복가입 확인** — DI 저장하되 차단 로직 없음.

### 코드 밖 / 운영 (담당·일정 공유)
- **포트원 채널 + 다날 CPID 개통 신청** — 제출 정보: 상호·사업자번호·CPID·서비스 URL·개인정보처리방침 URL·업종·필요사유. 리드타임 최대 1주. CPID 심사가 약관·처리방침 URL 게재를 확인.
- **약관 문안** — Sanity `term`(context=`identity_verification`) 본문 채우기(legal_policy 메모 pre-launch TODO).
- **prod env** — `PORTONE_API_SECRET/STORE_ID/CHANNEL_KEY`, `IDENTITY_CRYPTO_KEY`, `IDENTITY_VERIFICATION_MODE=real`(개통 후).

## 관련 메모리

- `identity-verification-model` — 본인확인 시점/공유/무만료/방식 전환 결정
- `legal_policy_decisions` — pre-launch TODO(휴대폰본인인증 전환·term 본문·처리방침 정합)
