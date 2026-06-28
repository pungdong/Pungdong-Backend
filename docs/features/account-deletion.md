# 회원탈퇴 · 개인정보 파기 (account deletion & anonymization)

> 피처 문서 = **정책 · 왜 · 히스토리**. 구현(엔드포인트·흐름·권한)은 [account 도메인](../../src/main/java/com/diving/pungdong/account/CLAUDE.md) · [docs/architecture/sign-up.md](../architecture/sign-up.md) 링크로.

여러 도메인(account · payment · notification · S3)에 걸치고 **법적 의무 + 앱스토어 심사 요건**이 얽혀 있어 정책을 한곳에 박아둔다. 코드만 보면 "왜 하드 삭제가 아니라 익명화인지", "왜 30일을 들고 있는지"가 안 보인다.

---

## 한 줄 요약

회원탈퇴는 **soft delete(즉시 접근 차단) → 유예기간 30일(복구 가능) → PII 익명화(복구 불가)** 의 3단계다. `account` row 자체와 **법정 보존 의무가 있는 결제·계약 기록은 남기고**, 신원을 식별하는 PII 만 파기한다.

---

## 왜 "하드 삭제"가 아니라 "익명화 + row 보존"인가

두 개의 법이 정반대 방향으로 당긴다:

- **개인정보보호법 §21** — 목적 달성 시 개인정보를 *지체 없이 파기*하라. → 다 지워라.
- **전자상거래법 시행령 §6** — 결제·계약 기록은 일정 기간 *보존*하라. → 지우지 마라.

이 앱은 토스 결제로 유료 강습을 중개하므로 **결제/계약 기록은 보존 의무 대상**이고, 그 기록은 `account` 를 FK 로 참조한다. 그래서 계정 row 를 **하드 삭제할 수 없다**(무결성 깨짐). 해법은 하나뿐 — **row 는 남기되 식별정보(PII)만 파기(익명화)**. 사용자의 "탈퇴시키되 자료는 일정기간 유지" 직감이 결과적으로 맞는 이유가 이것이다. 단, "자료 전부 유지"가 아니라 **"법이 보존하라는 것만, 나머지 PII 는 파기"** 가 정확한 형태다(전부 들고 있으면 §21 위반 + Google Play 리젝).

### 데이터 3분류

| 종류 | 처리 | 근거 |
|---|---|---|
| 보존의무 **없는 PII** — 이메일·전화·이름(닉네임)·생일·성별·비밀번호·프로필사진·푸시토큰 | **익명화/삭제** | 개인정보보호법 §21 (지체 없이 파기) |
| **법정 보존** 기록 — 결제·계약·청약철회 기록(5년), 소비자 분쟁처리 기록(3년) | **유지**(최소화·분리) | 전자상거래법 시행령 §6 |
| 계정 row 자체 — `id`, `isDeleted`, `deletedAt`, `anonymizedAt` | **유지** | 결제·예약 FK 무결성 |

> 보존 기간(5년/3년)은 결제·정산 도메인이 별도 소유한다. 이 문서는 *account PII 의 파기*까지를 다룬다.

### 익명화 범위 — 현재 vs 후속

현재 `AccountAnonymizationService` 는 **`account` 엔티티의 PII 까지만** 파기한다(이메일·전화·이름·생일·성별·비밀번호·프로필사진·FCM 토큰). 처리방침 §1 이 수집한다고 명시하는 **본인확인(CI/DI)·강사 자격·정산 정보**는 별도 도메인 소유라 아직 익명화 대상이 아니다 → 처리방침 §7 문안과 갭이 있다. 런칭 블로커(앱스토어 계정삭제)는 account PII 익명화로 충족되므로, 도메인 확장은 **법정 보존 분리와 함께 후속**으로 분리했다. → [#131](https://github.com/pungdong/Pungdong-Backend/issues/131)

---

## 유예기간을 두는 이유 (30일)

탈퇴 즉시 PII 를 파기하지 않고 **30일 보유 후 익명화**한다. 이유:

1. **오탈퇴 / 계정탈취 복구 안전망** — 결제 이력이 있는 유료 서비스라 "실수로 날렸다"의 비용이 크다.
2. **복구 플로우와 결합** — `PATCH /account/deleted-state`(이메일 인증 복구)는 *유예기간이 있어야* 성립한다. 즉시 익명화하면 이메일이 사라져 복구가 불가능해진다.
3. **업계 표준** — 카카오·네이버 등 7~30일 유예가 보편적이라 심사·약관에서 설명하기 쉽다.

유예 동안의 상태: **접근은 즉시 차단**(로그인 불가·토큰 무효)하되 PII 는 복구용으로 보유. "탈퇴 = 즉시 못 쓰지만 30일은 되돌릴 수 있음".

> 유예 기간은 `pungdong.account.deletion.grace-days`(기본 30). 익명화 sweep cron 은 `pungdong.account.deletion.sweep-cron`(기본 매일 04:30).

---

## 왜 비밀번호 재확인을 받지 않나

`DELETE /account` 는 **요청 본문 없이** 호출한다 — 로그인 세션(JWT) 자체가 본인 증명이라 비밀번호를 다시 받지 않는다. 본인확인은 **FE 의 "의도 확인"**(체크박스 + 버튼; GitHub 의 repo 이름 타이핑류)으로 처리한다. 근거:

1. **세션 = 본인 증명** — 탈퇴는 인증된 세션에서만 호출된다. 표준 패턴은 재인증이 아니라 "의도 확인"이고, 저장소의 [security.md](../architecture/security.md) "identity from session, never input" 원칙과도 맞는다(본인 식별을 입력값이 아니라 세션에서).
2. **안전망은 비번이 아니라 복구** — 실수/악의 삭제의 회수는 비밀번호 게이트가 아니라 **soft delete + 30일 이메일-인증 복구**가 담당한다(위 유예기간 절).
3. **"탈퇴 ≤ 가입 난이도" 원칙** — 개인정보보호법 계열·앱스토어 정책이 탈퇴를 가입보다 어렵게 만들지 말 것을 요구한다. 가입에 없는 비번 재확인을 탈퇴에 강제하면 역행.
4. **소셜로그인 대비** — 곧 도입할 OAuth(Kakao/Naver) 계정은 비밀번호가 없어, 비번 필수면 탈퇴 자체가 불가능해진다(선결 과제).

**하위호환**: 비번을 동봉하던 구버전 앱 빌드도 그대로 통과한다 — BE 가 `@RequestBody` 를 받지 않아 본문을 무시하고 204 를 돌려준다(검증하지 않음). FE 신버전(PR #463)은 본문 없이 호출한다.

---

## 3단계 생애주기

```
탈퇴 요청 (DELETE /account, 본문 없음 — 세션이 본인 증명)
  │  isDeleted=true, deletedAt=now
  │  강의 일괄 close (강사인 경우)
  │  현재 access token 즉시 블랙리스트 (Redis)
  ▼
[유예 30일]  ← 로그인·refresh 차단, PII 보유 → 복구 가능 (PATCH /account/deleted-state, 이메일 인증)
  │  deletedAt + graceDays 경과
  ▼
익명화 (AccountDeletionScheduler → AccountAnonymizationService, 일 1회 sweep)
  │  email → deleted_{id}@deleted.local   (유니크 슬롯 해제 → 같은 이메일 재가입 가능)
  │  password → 랜덤(로그인 불가), nickName/phone/birth/gender/selfIntroduction/socialId → null
  │  프로필사진 → S3 객체 삭제(공유 기본 이미지는 제외) + 행 삭제
  │  푸시토큰(FCM) → 전량 삭제
  │  anonymizedAt = now   ← 멱등 가드(이미 있으면 sweep 이 건너뜀)
  ▼
잔존: account row + 법정 보존 기록(결제/계약). 복구 불가.
```

### 탈퇴 즉시 접근을 끊는 두 개의 빗장

JWT 는 stateless 라 발급 후 만료 전까지 유효하다. 탈퇴 *직후* 접근을 끊으려면 양쪽을 다 막아야 한다:

- **access token** — `DELETE /account` 가 현재 토큰을 Redis 블랙리스트에 등록(로그아웃과 동일 경로). `JwtAuthenticationFilter` 가 다음 요청부터 거부.
- **refresh token** — `SignController.refresh` 가 `isDeleted` 계정의 재발급을 거부. (블랙리스트만으로는 refresh 우회가 뚫리므로 가드를 같이 둔다.)
- **이메일/비번 로그인** — `AccountService.findAccountByEmail` 이 `isDeleted` 계정을 이미 차단.

### 익명화의 멱등성 (왜 중요한가)

ECS 가 실패 태스크를 빠르게 재시작 → 같은 sweep 이 **동시/재시도 실행**될 수 있다. `anonymizedAt != null` 이면 건너뛰는 가드 덕에 중복 실행이 안전하다(ShedLock 불필요). 각 계정은 **독립 트랜잭션**으로 익명화돼 한 건 실패가 나머지를 막지 않는다. (마이그레이션 멱등 원칙과 같은 맥락 — [deployment.md](../architecture/deployment.md).)

---

## 앱스토어 / 플레이스토어 요건

| 요건 | 충족 방식 |
|---|---|
| App Store 5.1.1(v) — 앱 내 계정 삭제 | 앱 설정 → 회원탈퇴 → `DELETE /account` |
| Google Play — 계정 **및 데이터** 삭제 | soft delete + 유예 후 PII 익명화(이 파이프라인). 보존 항목은 데이터 안전성 폼·처리방침에 명시 |
| Google Play — **웹에서** 삭제 요청 (앱 재설치 없이) | 웹 로그인 → 설정 → 회원탈퇴(같은 `DELETE /account`). **로그인 게이트여도 충족** — Google 은 "삭제 경로가 존재하고 외부에서 도달 가능"이면 되고 인증 요구를 허용한다. Play Console 데이터 안전성 폼에 이 URL 기입. |

---

## 손으로 해야 하는 것 (코드 밖)

- **개인정보처리방침 개정 적용 완료** (2026-06-29, Sanity `legalDocument` slug=`privacy`) — §7(파기)에 30일 유예·복구·익명화·법정보존 분리 명시, §1 에 프로필사진·푸시토큰 추가, §3 에 §7 파기 연결, §6 에 탈퇴 경로(설정>회원탈퇴·웹) 명시. **미런칭이라 버전 v1.0·시행일 유지**(고지의무 7일 전 공지 불필요 — 시행 전 수정). (plop.cool/privacy)
- **Play Console 데이터 안전성 폼**에 웹 삭제 URL(로그인→설정→회원탈퇴) 기입.
- 결제/계약 기록의 **5년 후 실제 파기**는 정산·결제 도메인의 별도 보존정책으로 (이 파이프라인은 account PII 까지).

---

## 구현 위치 (어떻게 — 도메인 링크)

- 엔티티 필드 `deletedAt`/`anonymizedAt`, 마이그레이션 `V4__account_deletion_anonymization.sql` → [account 도메인](../../src/main/java/com/diving/pungdong/account/CLAUDE.md)
- `AccountAnonymizationService`(PII 파기·멱등), `AccountDeletionScheduler`(일 1회 sweep, `@Profile("!test")`)
- 토큰 무효화 흐름 → [docs/architecture/sign-up.md](../architecture/sign-up.md) "토큰 정책"
- 안전망: `src/test/.../usecase/AccountDeletionUseCaseTest` (W=탈퇴·차단 / R=복구 / A=익명화·멱등·유예)

---

## 결정 히스토리

- **2026-06-29** — 출시 전 App Store/Google Play 계정삭제 의무가 하드 블로커로 식별. 기존 `DELETE /account` 는 `isDeleted` 플래그만 뒤집고 PII 를 그대로 뒀음(심사 리젝 + §21 위반 소지). 유예 30일 + PII 익명화 파이프라인 추가. soft delete + row 보존을 택한 결정적 이유 = 토스 결제기록의 법정 보존 의무(하드 삭제 불가). (PR #130)
- **2026-06-29 (정책)** — **유예기간 30일 채택** (즉시 익명화 대신). 근거: 오탈퇴/계정탈취 복구 안전망 + 기존 이메일-인증 복구 플로우와의 결합 + 업계 표준(카카오·네이버 7~30일). 즉시 파기는 복구 플로우를 무력화하므로 배제.
- **2026-06-29 (정책)** — **개인정보처리방침 문안 개정 적용** (Sanity `legalDocument` slug=`privacy`, 미런칭이라 v1.0 유지). 기존 §7 "지체 없이 파기" 가 30일 유예 보관과 충돌 → 유예·복구·익명화·법정보존 분리를 명시하도록 개정. 코드(실제 동작)와 처리방침(공시)을 정합화.
- **2026-06-29 (정책)** — **탈퇴 시 비밀번호 재확인 제거** (FE 이슈 #462). 기존 `DELETE /account` 는 본문에 현재 비밀번호를 받아 검증했으나, FE 가 의도확인(체크+버튼) 방식으로 전환하면서 선결 요청. 근거: 세션이 본인 증명(재인증 불필요) + soft delete·30일 복구가 실 안전망 + "탈퇴 ≤ 가입 난이도" 원칙 + 소셜로그인(비번 없음) 대비. `PasswordInfo` DTO 와 `checkCorrectPassword` 호출 제거, 구버전 앱의 비번 동봉은 무시(하위호환). FE PR #463 머지의 선결 배포.
- **2026-06-29 (후속 분리)** — 본인확인(CI)·강사 자격·정산 데이터의 탈퇴 파기는 법정 보존 분리와 함께 별도 작업으로 분리. → [#131](https://github.com/pungdong/Pungdong-Backend/issues/131)
