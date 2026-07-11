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

### 입력 형식 검증 — 유료 외부 호출 앞에서 (2026-07-10)
**형식조차 안 보고 외부 본인확인기관(다날)을 호출하지 않는다.** 실존·해지·명의 일치 판정은 다날 몫이지만, 구조적으로 불가능한 값(접두사가 `01`이 아닌 번호, 13월 생일, 5자리 OTP)은 BE 가 거른다. FE 검증은 UX 용이지 방어선이 아니다 — 콘솔·직접 호출로 우회된다.

- **검증 위치는 요청 DTO(`@Valid`)** — 서비스의 발송 쿨다운 획득(`acquireSendSlot`)보다 **앞서 돈다**. 그래서 오타 한 번이 사용자의 30초 쿨다운을 태우지 않는다. (서비스 안에서 검증했다면 태웠다.) 안전망: `IdentityVerificationRateLimitUseCaseTest` RL3.
- **정규화 후 검증** — `phoneNumber`/`birth` 는 setter 에서 구분자를 떼고(`010-1234-5678` → `01012345678`) canonical 형태를 검증·저장·전송한다. 전엔 하이픈째로 다날에 나갈 수 있었다(`real` 미검증이라 잠복).
- **`otp` 는 6자리 숫자만** — 아니면 400 이고 `attemptCount`(5회 한도)를 **소모하지 않는다**. 시도 횟수는 진짜 추측만 센다. (confirm 은 SMS 비용은 없지만 시도 한도를 깎는다.)
- 반환은 **400**(형식 오류 = malformed input). "인증번호 불일치"는 여전히 **200 + `errorCode`**(정상 분기) — 레포 규약대로 둘을 섞지 않는다.

### 한국 종속 인벤토리 (글로벌 확장 시 건드릴 것)
이 도메인은 **국가 중립이 아니다.** 일본 등으로 확장할 때 "번호 regex 를 파라미터화"하는 게 아니라 **`IdentityVerifier` 뒤에 새 구현을 붙이고 레코드 모양을 바꾸는** 일이 된다. 추상화를 미리 짓지 않는 대신, 결합 지점을 여기 목록으로 고정해 둔다. (`docs/architecture/time-handling.md` 가 필드 인벤토리로 한 것과 같은 방식 — 추상화 말고 목록화.)

| 결합 지점 | 코드 | 일본에 가면 |
|---|---|---|
| 번호 형식 | `KoreanMobileNumber.PATTERN` | `+81` 규칙 (`070/080/090`) |
| 통신사 | `Carrier`(SKT/KT/LGU/`*_MVNO`) | docomo/au/SoftBank — 다른 enum |
| 고유식별정보 | `IdentityVerification.ci` / `.di` | **개념 없음**(犯収法 eKYC) → 컬럼 자체가 달라짐 |
| 내·외국인 | `ForeignerType` | 다른 축 |
| 기관/연동 | `RealPortOneIdentityVerifier`(포트원→다날) | 다른 사업자·다른 법적 근거·다른 약관 |
| 필수 약관 | Sanity `term`(context=`identity_verification`) | 관할별 문안 |

**왜 지금 국가 필드를 안 만드나** — 저장된 모든 row 가 예외 없이 KR 이라, 나중에 E.164(`+8210…`)로 옮기려면 `UPDATE … CONCAT('+82', …)` 한 줄이다. **되돌릴 수 있는 결합**이라 지금 값을 치르지 않는다. (반대로 시간의 naive `LocalDateTime` 은 되돌릴 수 없이 모호해져서 지금 UTC 로 옮긴다 — [time-handling.md](../architecture/time-handling.md) §2 가 그 대가를 기록.) 확장점(`IdentityVerifier` 인터페이스 + `mode` 프로퍼티)은 **이미 있다**.

**가입 시 `country` 는 안 받는다** — ⑴ "국가"는 실은 국적·거주국·마켓/리전·번호 국가코드 4개의 다른 값이고 한 필드로 합치면 나중에 푸는 게 비용이다(한국 사는 일본인이 한국 번호로 인증). ⑵ 어느 기관을 부를지는 **인증 시점의 번호 국가코드/사용자 선택**에서 오지 가입 필드에서 오지 않는다. ⑶ 이 레포는 이미 "신원 속성은 가입이 아니라 본인인증 게이트에서 모은다"고 정해뒀다(`SignUpUseCaseTest` S3). market/region 개념이 실제로 생길 때 재검토 — [time-handling.md](../architecture/time-handling.md) §3 이 유저별 TZ 설정(전략 B)을 미룬 것과 같은 이유.

## 결정 히스토리

| 시점 | 결정 | 근거/PR |
|---|---|---|
| 2026-06-03 | 가입엔 본인확인 없음, 수강/강사 시점에 수집 | memory `identity-verification-model` |
| 2026-06-09 | 본인확인을 **계정 공유 도메인으로 승격** + `GET /me`(skip) | #38 |
| 2026-06-12 | 간편인증→휴대폰 전환 가능성 예고(CI 안정 확보) | memory |
| 2026-07-07 | **휴대폰 SMS(다날) + 포트원 REST v2 실연동**. status 다단계·carrier 입력·method 판별자·CI/DI 암호화. 무만료 유지·동의 둘 다 확정 | #163 |
| 2026-07-08 | **수강신청 선행 게이트 연동** — `EnrollmentService.submit` 이 세션 계정 최신 VERIFIED 조회. 강사·수강 공유 403 신호 `IdentityVerificationRequiredException`(-1017), 강사의 없는/남의 id 는 400 유지 | #164 |
| 2026-07-10 | **요청 형식 검증 도입**(휴대폰·생년월일·OTP) + 정규화. KR 규칙을 그대로 박고, **국가 추상화·가입 시 `country` 필드는 만들지 않는다** — 결합이 되돌릴 수 있어서(위 인벤토리). 검증은 DTO 에 둬 쿨다운보다 앞서게 | 이 PR |

## 미해결 / 확장

- 🔴 **실 다날 라이브 검증** — 포트원 공식상 **다날 SMS 는 실 계약 전 테스트 자체가 불가**([헬프센터](https://help.portone.io/content/cellphone-identity-verification))라 **CPID 개통(계약 ~2026-07-25 예정) 후**에만 실호출 검증 가능. 개통 전까지 `RealPortOneIdentityVerifier` 는 REST 명세·예제 기반(미검증). 개통 시 **필드 형식(phone 숫자만·birth `yyyy-MM-dd`)·응답 경로·OTP 에러코드·내외국인 판별**을 실응답으로 확정 — 권위 출처 표와 체크리스트는 [architecture/identity-verification.md](../architecture/identity-verification.md) 의 "외부 계약". `birth` 8자리 입력은 포트원 요구가 아니라 우리 편의 결정(FE 세기복원)임을 그 표가 명시.
- 🟢 **수강 플로우 연동 완료 (2026-07-08)** — 강의 신청(`POST /enrollments`) 전 본인인증 선행 게이트. 세션 계정 최신 VERIFIED 없으면 403 -1017 → FE 본인인증 화면. 구현·에러표는 [architecture/identity-verification.md](../architecture/identity-verification.md)·[architecture/enrollment.md](../architecture/enrollment.md).
- 🟢 **DI 중복가입 확인** — DI 저장하되 차단 로직 없음.
- 🔴 **발송 실패 사유 세분화 (CPID 개통과 함께)** — 지금은 형식이 맞지만 **실존하지 않는/해지된/명의 불일치** 번호도, 포트원·다날 **인프라 장애**도 전부 `SMS_SEND_FAILED` 400 하나로 뭉쳐 온다(`RealPortOneIdentityVerifier.postExpectOk`). FE 는 그 메시지를 그대로 띄우므로 "장애"와 "그 번호로는 못 보냄"이 같은 화면이 된다. **실 응답 코드를 보고 문구를 갈라야 함** — 개통 후 raw 응답 로그로 보정(내외국인 판별 보정과 같은 묶음). 인프라 장애는 4xx 가 아니라 5xx 가 맞는지도 그때 함께 판단.
- 🟢 **형식 검증 (2026-07-10)** — 휴대폰·생년월일·OTP 형식을 BE 가 거른다(위 "입력 형식 검증"). 형식 통과 ≠ 발송 성공.

### 코드 밖 / 운영 (담당·일정 공유)
- **포트원 채널 + 다날 CPID 개통 신청** — 제출 정보: 상호·사업자번호·CPID·서비스 URL·개인정보처리방침 URL·업종·필요사유. 리드타임 최대 1주. CPID 심사가 약관·처리방침 URL 게재를 확인.
- **약관 문안** — Sanity `term`(context=`identity_verification`) 본문 채우기(legal_policy 메모 pre-launch TODO).
- **prod env** — `PORTONE_API_SECRET/STORE_ID/CHANNEL_KEY`, `IDENTITY_CRYPTO_KEY`, `IDENTITY_VERIFICATION_MODE=real`(개통 후).

## 관련 메모리

- `identity-verification-model` — 본인확인 시점/공유/무만료/방식 전환 결정
- `legal_policy_decisions` — pre-launch TODO(휴대폰본인인증 전환·term 본문·처리방침 정합)
