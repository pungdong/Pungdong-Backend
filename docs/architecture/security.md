# 보안 — 신원·인가 원칙 (security)

> 크로스커팅 보안 원칙 문서. 도메인별 권한 매트릭스는 각 `docs/architecture/<domain>.md`, JWT/필터 구현은 `global/security/`. 요청측 PII 규칙·HTTP 상태 규칙은 루트 [CLAUDE.md](../../CLAUDE.md) "Security model".

## 한 줄

**클라이언트를 신뢰하지 않는다.** 요청자의 신원과 권한은 **서버(세션)가 정하지, 클라가 보낸 값으로 정하지 않는다.** FE·프록시·스니퍼가 요청을 조작해도 "내가 누구인지", "이 자원에 접근할 수 있는지"는 못 바꾼다.

## 핵심 원칙

### 1. 신원은 세션에서만 — 클라 입력으로 받지 않는다

요청자의 account id 는 **항상 인증 principal(`@CurrentUser` / JWT)** 에서 꺼낸다. **절대 `@RequestParam`/`@PathVariable`/body 로 받지 않는다.**

- ❌ `GET /orders?userId=123` — 클라가 userId 를 보냄 → 124, 125… 로 바꿔 남의 것 조회(정보 유출).
- ✅ `GET /orders` + `@CurrentUser Account me` — 서버가 토큰에서 신원 확정. 조작 불가.
- 업계 사례: 쿠팡은 **userId 파라미터를 전부 제거**하고 세션에서 꺼내도록 일괄 수정 — 정보 유출 방지의 핵심.
- **세션에서 얻을 수 있는 값이면 클라 입력으로 노출하지 말 것.** BE 내부 로직에서만 다룬다(`customerKey` 처럼 본인 id 가 불가피하게 나가는 경우는 본인 것이라 enumeration 벡터가 아님 — 그래도 최소화).

### 2. 객체 단위 인가 검증 (anti-IDOR)

클라가 **자원 id**(회차·주문·신청·세션 id 등)를 주는 건 정상이다(그걸로 뭘 할지 식별). 단 **행위 전에 "요청자가 그 자원의 주인/권한자인지" 반드시 검증**한다. 없으면 **404(존재 숨김)** 또는 403.

- 남의 결제 취소·남의 신청 조회·남의 회차 변경 = **전부 차단**. 요청자 ≠ 수행자면 거부.
- 우리 구현 패턴: `requireMyRound`(EnrollmentService) · `requirePayable`/`confirm` 소유주 대조(PaymentService) · `requireForInstructor`(InstructorEnrollmentService) · `owner.getId().equals(me.getId())` → 아니면 `ResourceNotFoundException`(404, **존재 자체를 숨겨** 자원 탐지도 막음).
- **id 를 받는 모든 엔드포인트는 이 검증이 있어야 한다.** 빠지면 IDOR(Insecure Direct Object Reference) 취약점.

### 3. enumeration / 공개 식별자 (방어 심화 — 2차)

순차 auto-increment id 는 1,2,3… 으로 추측·순회가 쉽다. 단 **1·2가 1차 방어**다 — 받아주는 곳이 없고(1) 권한 검증이 있으면(2) 순차 id 여도 못 긁는다. 그 위에 심화로:

- **공개 노출되는 식별자는 비순차로.** 주문번호 = Hashids 난독화(`OrderNoFormatter`, [payment.md](payment.md)).
- **공개 프로필 등 "id 로 남을 조회"하는 기능을 만들 땐 account id 대신 핸들**(`nickName`, 이미 공개·비순차·비PII)로. 예: `GET /instructors/{nickName}`(인스타 `@handle` 방식). → account id 노출 0 + enumeration 불가.
- 응답 body 의 raw account id(`instructorId` 등)도 가능하면 핸들/표시명으로 대체.

## 현재 준수 상태 (2026-06-28)

| 원칙 | 상태 |
|---|---|
| 신원=세션 | ✅ account id 를 입력(param/path)으로 받는 엔드포인트 **0개**. 컨트롤러 24개가 `@CurrentUser`. |
| 객체 인가 | ✅ id 받는 행위는 소유/권한 대조 후 처리(`requireMy*`, owner 대조 → 404 존재숨김). |
| 비순차 식별자 | 🟡 주문번호 ✅(Hashids). account id 는 응답에 일부 노출(공개 course 의 `instructorId` 등) — 받는 엔드포인트가 없어 현재 악용 불가. |

## 워치리스트 (회귀 방지)

- 새 엔드포인트 추가 시 **신원은 `@CurrentUser` 로, 자원 id 는 소유 검증** — 둘 다 기본 체크.
- **account/user id 를 param 으로 받는 시그니처가 보이면 = 레드 플래그.** 세션에서 꺼내도록 수정.
- **공개 식별자 전략(별도 안건)**: `Instructor Profile`(공개 프로필) 등 "id 로 남을 조회"하는 기능의 **선행조건** — 핸들 기반으로 설계. (GitHub 이슈로 트래킹.)

## 관련

- 루트 [CLAUDE.md](../../CLAUDE.md) "Security model" — PII 는 POST body / HTTP 상태는 결과 반영 / JWT 401·403 JSON.
- `global/security/` — `JwtTokenProvider`·`JwtAuthenticationFilter`·`@CurrentUser`·`SecurityConfiguration`(URL→role).
- [payment.md](payment.md) — `OrderNoFormatter`(주문번호 난독화).
