# 결제 (payment) 도메인

## 1. 한 줄 요약

수강신청(`enrollment` = `PENDING` 미결제)의 **결제**를 책임지는 도메인. **선결제**라 신청 직후가 결제 시점이다(전 회차 동일). **PG 중립**(포트-어댑터/Strategy) — FE 결제창이 결제하고 **승인은 서버가** 호출한다. 실제 PG 는 `PaymentGateway` 뒤에서 교체된다(토스/이니시스/stub). **토스↔이니시스는 `PAYMENT_MODE` 환경변수로 갈아끼우는 플러그식 스왑**이고, **신규 주문**은 전역 설정(`pungdong.payment.mode`)이 PG 를 고르지만 그 PG 를 **주문에 박제**(`PaymentOrder.provider`)해서 **기존 주문의 승인·환불은 결제 당시 PG 로** 간다(`PaymentGatewayRegistry`) — PG 를 갈아탄 뒤 과거 주문 환불이 엉뚱한 PG 로 나가 실패하는 것을 막는다. 핵심 invariant 두 개: **(1) 금액은 서버 권위값** — 클라이언트가 보낸 amount 를 신뢰하지 않고 주문에 박힌 금액과 대조하며, PG 에도 **주문에 박힌 금액**을 보내 결제창 결제액과 다르면 PG 가 거절, **(2) 결제 완료 = 전이** — 승인 성공만이 enrollment 를 다음 상태로 넘긴다(**전 회차** `PENDING→ACCEPT_PENDING`·강사 결정 대기). 강사 거절·학생 취소·무응답 만료 시 enrollment 이벤트로 **전액 자동환불**(더 싼 슬롯으로 일정이 바뀌면 **차액만** 환불)(payment→enrollment 방향, [enrollment.md](enrollment.md) §3-2). 시크릿(토스 시크릿키 / 이니시스 hashKey·apiKey)은 BE 밖으로 안 나간다(juso 승인키 기조).

> 레거시 `domain/payment/Payment`(옛 예약 플로우의 가격 산술 전용, PG 필드 없음)와 무관 — 새 `payment/` feature 패키지가 enrollment 옆에서 결제를 1급으로 소유.

## 2. 컴포넌트 지도

```mermaid
flowchart TB
    subgraph payment["payment 도메인"]
        Ctl["PaymentController<br/>/prepare · /confirm · GET /orders/{id}"]
        InicisRet["InicisReturnController<br/>POST /payments/inicis/return (permitAll)"]
        Svc["PaymentService<br/>(@Service enrollmentPaymentService)"]
        Order["PaymentOrder (엔티티)<br/>PaymentOrderJpaRepo"]
        Reg["PaymentGatewayRegistry<br/>active()=전역설정 · forOrder(provider)=주문박제"]
        Client["PaymentGateway (interface)<br/>provider·initParams·confirm·cancel"]
        Toss1["TossPaymentGateway (빈)"]
        Inicis["InicisPaymentGateway (빈)"]
        Stub["StubPaymentGateway (빈)"]
    end
    Ctl --> Svc
    InicisRet --> Svc
    Svc --> Order
    Svc --> Reg
    Reg --> Client
    Client -. 구현 .- Toss1
    Client -. 구현 .- Inicis
    Client -. 구현 .- Stub
    Svc -- "상태 읽기/확정(CONFIRMED)" --> Enr["enrollment.Enrollment<br/>(EnrollmentJpaRepo)"]
    Svc -- "라이브 수강료" --> Course["course.Course"]
    Toss1 -- "POST /v1/payments/confirm<br/>Basic 시크릿 키" --> TossApi["토스페이먼츠 API"]
    Inicis -- "승인(payAppl.ini)·환불(iniapi)<br/>P_CHKFAKE / hashData 서명" --> InicisApi["KG이니시스 API"]
```

단방향: payment → enrollment / course (읽기 + enrollment 확정). enrollment/course 는 payment 를 모른다.

## 3. 핵심 흐름

토스/STUB 는 FE 가 `confirm` 을 호출하지만, **이니시스는 결제창이 BE 콜백으로 form POST → BE 서버승인**(WebView 가 POST 본문을 못 읽어서). 아래는 이니시스 흐름:

```mermaid
sequenceDiagram
    participant FE
    participant Ctl as PaymentController
    participant Ret as InicisReturnController
    participant Svc as PaymentService
    participant PG as InicisPaymentGateway
    Note over FE: 신청 직후 enrollment = PENDING (미결제) — 전 회차 동일
    FE->>Ctl: POST /payments/prepare {roundId, mobile, client}
    Ctl->>Svc: prepare(student, roundId, mobile, client)
    Svc->>Svc: 소유·미결제(PENDING) 검증 + 권위 금액 재계산
    Svc->>PG: initParams(orderId, 금액, 상품명, mobile)
    Note over PG: P_ 파라미터 + 서명 P_CHKFAKE 계산(외부 호출 없음)
    Svc-->>FE: {orderId, amount, provider:INICIS, params:P_*} (READY 주문)
    Note over FE: INIPayPro_v2.js 로 결제창 구동 → 사용자 인증
    FE-->>Ret: 결제창이 P_NEXT_URL 로 form POST (P_OID·P_STATUS·P_AUTH_TID·P_IDCNAME)
    Ret->>Svc: confirmByCallback(orderId, {P_AUTH_TID, P_IDCNAME})
    Svc->>PG: confirm(orderId, 권위금액, pgPayload)
    Note over PG: payAppl.ini 서버승인 — 금액은 주문 권위값, 호스트는 P_IDCNAME allowlist
    PG-->>Svc: approved + P_TID (또는 거절→400)
    Svc->>Svc: 주문 DONE + enrollment 전이(PENDING→ACCEPT_PENDING·강사 결정 대기)
    Ret-->>FE: 302 redirect (web URL / plop:// , 성패)
```

분기: 인증 실패(`P_STATUS≠00`) → 승인 호출 없이 fail 302. PG 승인 거절 → 주문 READY 유지 + fail 302(에러를 PG 에 안 던짐). 알 수 없는 주문(위조 P_OID) → web fail 302. 이미 DONE 주문 → 200 DONE(멱등). 미결제(PENDING) 아닌 신청 prepare → 400. 비소유 → 400(존재 숨김). **금액은 콜백값(P_AMT)이 아니라 주문 권위값**으로 승인 전문에 실려 위변조를 막는다(승인엔 서명이 없음).

### 슬롯 변경 차액 결제 — 대기를 "예약"이 아니라 "주문"에 둔다

더 비싼 시간대로 옮기려면 차액을 받아야 하는데, 그 "결제 대기"를 예약 상태({`EnrollmentStatus`})에 두면 방금 없앤 `PAYMENT_PENDING` 류가 되살아난다. 대신 **대기를 주문에** 둔다:

```
POST /payments/prepare {roundId, targetDate, targetTicketRef, targetBlockStart, targetBlockEnd}
  ├─ enrollment 가 검증·가격 산정(quoteSlotChange) — 위치·장비 고정이라 갈리는 건 입장료뿐
  ├─ amount = 목표 회차금액 − 현재 회차금액 (차액만)
  ├─ 주문에 목표 슬롯 박제 + 목표 슬롯 좌석 hold(주문 귀속, paymentTtlHours)
  └─ 회차 상태는 그대로 (ACCEPT_PENDING / CONFIRMED)
POST /payments/confirm
  └─ 승인되는 순간 applySlotChange — 슬롯 교체 + hold→실점유 전환 + 옛 슬롯 이력. 상태는 여전히 그대로
[미결제 방치 · paymentTtlHours 경과]
  └─ 주문만 FAILED + 좌석 반납. 예약은 원래 슬롯 그대로 — 롤백할 것이 없다
```

- **왜 좌석을 잡나**: 결제창이 떠 있는 동안 그 자리를 다른 학생이 가져가면 **돈은 받고 자리는 못 주는** 상태가 된다. 강사 제안 hold 와 같은 이유·같은 부품(`AvailabilityHold`)이되, **주문 귀속**(`paymentOrderId`)이라 제안 TTL 스위퍼가 걷어가지 않는다.
- **왜 강사 재수락이 없나**: 학생이 자기 돈을 더 내고 같은 위치·같은 코스의 다른 시간대로 옮기는 것이라 강사 결정 대상이 아니다. 회차 상태를 안 건드리므로 24h 시계도 재시작되지 않는다.
- **범위**: 바뀌는 건 **일정(날짜·이용권·블록)** 뿐. 위치·장비까지 바꾸려면 취소 후 재신청(주문에 실을 수 있는 건 일정 한 벌).
- 목표 입장료는 별도 컬럼 없이 유도한다 — 위치·장비가 그대로라 `목표입장료 = 현재입장료 + 차액`.

## 4. 데이터 모델

```mermaid
erDiagram
    ENROLLMENT_ROUND ||--o{ PAYMENT_ORDER : "한 회차의 결제(들)"
    PAYMENT_ORDER {
        Long id
        String orderId "unique · PG 주문번호(=P_OID)"
        Long enrollment_round_id "FK"
        int amount "서버 권위 금액(원)"
        int refundedAmount "누적 환불액 · 잔액=amount-refundedAmount (V14)"
        String orderName "코스명 (N회차)"
        PaymentStatus status "READY|DONE|CANCELED|FAILED"
        PaymentProvider provider "결제 당시 PG(박제) · TOSS|INICIS|STUB"
        PaymentClient client "web|app (콜백 리다이렉트 타겟)"
        String paymentKey "PG 거래식별자(이니시스 P_TID) · 승인 후"
        String method "승인 후"
        OffsetDateTime approvedAt "승인 후"
        LocalDate targetDate "차액결제: 적용할 목표 슬롯 (V16)"
        String targetTicketRef "차액결제 목표 이용권 (V16)"
        LocalTime targetBlockStart "차액결제 목표 시작 (V16)"
        LocalTime targetBlockEnd "차액결제 목표 종료 (V16)"
    }
    PAYMENT_ORDER ||--o{ REFUND_ORDER : "환불 시도(원장)"
    REFUND_ORDER {
        Long id
        Long payment_order_id "FK"
        int amount "요청/취소 금액(원)"
        String reason
        RefundStatus status "REQUESTED|DONE|FAILED (V15)"
        OffsetDateTime createdAt "시도 시각(선기록)"
        OffsetDateTime completedAt "결과 확정 시각 · REQUESTED 면 null (V15)"
        String failureCode "PG 거절코드 (V15)"
        String failureMessage "PG 거절사유 (V15)"
    }
```

설계 의도: `orderId`(=P_OID) 가 unique + 멱등 키(콜백→주문 매핑, amount 조회 키). `amount` 는 prepare 시점에 **코스 라이브 수강료 + 입장료 스냅샷 + 장비 스냅샷** 으로 재계산해 박는다. `provider` 는 prepare 가 박제(V10), 승인·환불은 그 값으로 라우팅. `client` 는 콜백 리다이렉트 타겟(V11). 한 회차에 READY 주문은 하나만 멱등 재사용.

### 돈의 축 vs 예약의 축 — 그리고 주문을 어떻게 읽나

`PaymentStatus`(주문)와 `EnrollmentStatus`(회차)는 **독립**이다. `DONE` 은 "PG 승인됨"이지 "예약 확정"이 아니다 — 선결제라 승인 시점의 회차는 `ACCEPT_PENDING`(강사 결정 대기)이고 확정은 강사 수락 뒤다.

환불은 **주문 단위로 실행**(PG 취소 전문에 그 주문의 `paymentKey` 를 실어야 함)되고, `refund_order` 에 이력이 쌓인다. 회차는 그 위의 **집계 단위**다 — 한 회차에 승인 주문이 여러 건일 수 있다(원결제 + 일정 변경 차액 결제).

> **회차 순액 = Σ(승인액) − Σ(환불액)** = Σ(주문별 `refundableAmount()`)
>
> - **회차 전액 환불**(강사 거절·학생 취소·무응답) = 그 회차의 승인 주문을 **각각** 잔액 전액 취소하는 루프
> - **차액 환불** = **최신 주문부터** 뺀다 — 차액 결제분을 먼저 되돌려야 원결제가 부분환불된 것처럼 보이지 않는다. 최신 주문 잔액을 넘으면 이전 주문으로 넘어가고, **회차 순액을 넘지는 않는다**
> - 불변식 **"회차 순액 == `chargeTotal()`"** 은 주문이 N개가 돼도 성립한다(차액을 더 받든 돌려주든 양쪽이 같이 움직이므로) 승인 사실인 `status` 는 부분환불로 바뀌지 않으므로, **잔액을 행에서 바로 읽히게** `refundedAmount` 를 함께 들고 있는다(V14).

| 주문이 이렇게 보이면 | 뜻 |
|---|---|
| `DONE` + `refundedAmount = 0` | 정상 결제 |
| `DONE` + `0 < refundedAmount < amount` | **부분환불**(일정 변경 차액 등) |
| `CANCELED` (`refundedAmount = amount`) | **전액환불** |

`refund_order`(이력)가 **원장**이고 `refundedAmount` 는 그 합의 **캐시**다 — 같은 트랜잭션에서 함께 갱신되며, 어긋나면 `refund_order` 가 진실이다. 모든 환불은 `RefundService.applyCancel` 한 곳을 지나며 **취소가능 잔액으로 clamp** 된다(초과 취소·이중 취소 불가, 멱등).

### 환불은 "시도"부터 남긴다 — 대사(reconciliation) 가능한 원장

환불은 **롤백되지 않는 외부 부수효과**(PG 에 돈을 돌려주라고 말하는 것)다. 기록을 발행자(강사 거절·만료 sweep·학생 취소) 트랜잭션에 묶어두면 그 트랜잭션이 롤백될 때 **PG 엔 취소가 나갔는데 우리 DB 엔 흔적이 없는** 상태가 된다 — 재시도가 이중환불이 되고 대사도 불가능하다. 그래서 기록은 `RefundLedger` 가 **별도 트랜잭션**(`REQUIRES_NEW`)으로 맡는다.

```
applyCancel
  ├─ 대사 가드: 그 주문에 REQUESTED 잔존? → 건너뜀(사람이 PG 원장과 대사해야 재개)
  ├─ recordAttempt  → REQUESTED 즉시 커밋      ← 여기서 죽어도 "시도했다"는 남는다
  ├─ PG cancel
  │    ├─ 거절(PaymentGatewayException) → markFailed(FAILED + code/msg) 후 rethrow
  │    └─ 전송 실패(타임아웃·파싱)        → REQUESTED 유지 + rethrow (결과 미확인 = 대사 대상)
  └─ markDone(DONE + completedAt) + refundedAmount 누적 + 전액이면 CANCELED
```

| `refund_order.status` | 뜻 |
|---|---|
| `REQUESTED` | **결과 미확인** — PG 가 취소했는지 모른다. 그 주문의 자동 환불은 잠기고 **대사 대상**이 된다 |
| `DONE` | 취소 성공. 잔액(`refundedAmount`)에 반영됨 |
| `FAILED` | PG 가 거절. `failureCode`/`failureMessage` 에 진단정보(이니시스 `resultCode` / 토스 `code`) |

- **성공 후 발행자가 롤백되면**: 환불 기록·잔액은 커밋된 채 남는다(현실과 일치 — PG 는 취소했다). 다음 재시도는 줄어든 잔액을 보고 no-op → **이중환불 없음**. 상태 전이만 다시 시도된다.
- **실패하면**: 상태 전이는 롤백(돈-상태 원자성 유지)되지만 **`FAILED` 이력은 남는다**.
- PG 거절 사유는 `PaymentGatewayException` 이 실어 나르되 **응답엔 일반 400 문구만** 내려간다(PG 내부 코드를 강사 화면에 노출하지 않음). 진단은 DB·로그에만.

## 5. 보안 / 권한 매트릭스

| 엔드포인트 | 인증 | 소유권 검증 | 비고 |
|---|---|---|---|
| `POST /payments/prepare` | authenticated | round.enrollment.student == 나 + 상태 **미결제(PENDING)**, 전 회차 동일 | 비소유/없음 = 400, 미결제 아님 = 400 |
| `POST /payments/confirm` | authenticated | order.enrollment.student == 나 | **TOSS/STUB 전용**. amount 불일치 = 400, 멱등(이미 DONE = 200) |
| `GET /payments/orders/{orderId}` | authenticated | order.enrollment.student == 나 | 성공화면·재진입 조회. 비소유 = 400 |
| `POST /payments/inicis/return` | **permitAll** + **CORS 제외** | (P_AUTH_TID + 서버 권위 금액 대조가 방어) | 이니시스 결제창 form POST. 승인 후 302 리다이렉트(성패·web/app). `/payments/**` 보다 먼저 매칭. cross-origin form POST 라 CORS 검사에서 뺌(아래) |

**이니시스는 confirm 주체가 BE**: 앱 WebView 가 결제창의 form POST 본문을 못 읽어, `P_NEXT_URL` 을 BE(`/payments/inicis/return`)로 두고 서버가 승인 후 GET 리다이렉트(주문에 박제된 `client` 로 web URL/`plop://` 선택, 고정 allowlist=오픈리다이렉트 방지). TOSS/STUB 는 FE 가 confirm. 세션리스 승인은 소유권 대신 **`P_AUTH_TID`(우리 콜백에만 옴) + 승인 전문의 서버 권위 금액 대조**가 방어(승인엔 서명이 없음).

**CORS 제외**: 콜백은 결제창(`paypro.inicis.com`)·앱 WebView 가 하는 **cross-origin form POST(navigation)** 라, 전역 CORS allowlist(`cors.allowed-origins`)로 origin 을 검사하면 `Origin` 헤더가 밖이라 `Invalid CORS request`(403)로 막힌다. 하지만 form POST navigation 은 브라우저 JS(fetch/XHR)가 아니라 **CORS 의 대상이 아니고**, 콜백 진위는 위의 `P_AUTH_TID`+서버승인이 보장한다 — CORS 는 이 경로의 보안 경계가 아니다. 그래서 `SecurityConfiguration.corsConfigurationSource()` 가 `/payments/inicis/return` 만 `allowedOriginPattern("*")`(+`allowCredentials=false`)로 `/**` 보다 먼저 등록해 CORS 검사에서 뺀다. 전역 allowlist 는 그대로(다른 경로는 여전히 restrictive). FE 가 WebView origin 을 웹 도메인으로 위장할 필요가 없어진다.

**SSRF 방어**: 승인 호스트를 콜백 `P_IDCNAME`(예: `fc`→`fcpaypro.inicis.com`)으로 조립하므로, `idcHost()` 가 소문자 토큰만 허용해 `evil.com/` 같은 호스트 주입을 막는다.

**시크릿은 BE 전용** — 토스 시크릿키(승인 Basic 인증), 이니시스 `hashKey`(P_CHKFAKE 서명)·`apiKey`(환불 hashData). FE 엔 계산된 `params`(P_ 파라미터 + 서명값)만 내려간다. **`P_NEXT_URL`(콜백 주소)은 클라이언트가 정하지 못한다** — 서버 설정(`pungdong.payment.inicis.ret-url`) 고정.

## 6. 알려진 설계 간극

- 🟢 **이니시스 실 왕복 검증 완료** (2026-08-07, staging 테스트 MID `INIpayTest`) — **실카드**로 결제창→승인(payAppl)→DONE→CONFIRMED→**환불(iniapi)**→CANCELLED **전 사이클 성공**(카드 승인·취소 문자까지 수신). 저장한 tid 로 환불이 승인돼 **환불 tid 필드 OK**(P_TID 우선·P_APPL_TID 폴백 정상), hashData 바이트동일성·전액취소(type=refund) 정상. 검증법: raw JWT(Bearer 안 붙임) → `GET /enrollments/mine` → `POST /enrollments/{id}/refund`. prod MID(`plopol1192`)는 카드사심사 flip 때 재확인.
- 🔴 **webhook 미연동** — 비동기 상태(취소·부분취소 통보)를 받지 못한다. v1 은 콜백 승인 + 환불 API 동기 응답만. 카드+간편결제만 받아 가상계좌 입금통보는 불필요. → PG webhook 엔드포인트 + 서명 검증 후속(`venue/sync/SanityWebhookVerifier` 패턴 참고).
- 🟢 **환불 clientIp 등록 불요** (2026-08-07 검증) — 환불 전문의 `clientIp`(기본값·변동 egress)로 이니시스 환불이 통과했다. 즉 **고정 egress(fck-nat) 불필요** — 환불 자동화에 인프라 부담 0. (KCP 8012 취소-IP 제약과 달리 이니시스는 IP 대조를 안 하는 것으로 확인. prod MID 에서 재확인 권장이나 강신호. 만약 prod 에서 IP 제약이 나타나면 fck-nat/나노 NAT ~$7/월 옵션 — 히스토리는 git.)
- 🟢 **결제 미완 만료 + 거절/무응답 자동환불 구현** (2026-08-07 선결제 전환) — 선결제 1회차: 미결제(PENDING) 12h 만료(슬롯 해제·환불 없음), 결제완료(ACCEPT_PENDING) 강사 무응답 24h 만료 + **전액 자동환불**, 강사 거절 시 **전액 자동환불**. enrollment 이벤트(`EnrollmentRefundRequestedEvent`) → `EnrollmentRefundListener` → `RefundService.refundRoundFully`(동기·롤백 안전). 상태기계는 [enrollment.md](enrollment.md) §3-2.
- 🟡 **입장료/장비 live 재계산 안 함** — 권위 금액은 수강료만 라이브, 입장료/장비는 신청 스냅샷. venue 블록 재도출 후속.
- 🟢 **정산(지급대행) 미연동** — 강사 정산은 이니시스 **지급대행**이 대행한다(런칭엔 상점관리자페이지 수동 운영, 지급대행 API 는 후속). 플랫폼 수수료/포인트 분해 정산은 우리 로직이 계산(런칭엔 포인트 없음). → 정책은 [docs/features/payment.md](../features/payment.md).
- 🟢 **캘린더 표시** — 결제완료·점유 상태(`ACCEPT_PENDING`/`CONFIRMED`)를 `confirmed` 버킷으로 합산(점유). FE 가 "미결제(PENDING)"를 별도 표시하려면 카운트 분리 후속.

## 7. 더 깊게: 테스트로 보기

실제 동작의 단일 출처 = `src/test/java/com/diving/pungdong/usecase/PaymentUseCaseTest.java`(실 H2 + 시큐리티 체인, `PaymentGateway` 만 `@MockBean`). `@DisplayName` 위→아래로 사양을 읽는다:

- `P1` 신청(PENDING) 직후 prepare → READY 주문 + 권위 금액(365,000) + provider=STUB
- `P2` confirm 성공 → 주문 DONE + enrollment **ACCEPT_PENDING**(선결제 1회차 = 결제완료·강사 확인 대기)
- `P3` 금액 불일치 → 400, PG 미호출, 신청 그대로(PENDING)
- `P4` confirm 멱등(재호출도 DONE)
- `P5` 이미 결제완료(ACCEPT_PENDING) 신청 재-prepare → 400(결제대기 아님)
- `P6` 비소유 prepare → 400(존재 숨김)
- `P7` 신청(PENDING) 좌석 점유가 둘째 신청을 막음(정원 1) — 선결제라 결제·수락 전에도 점유
- `I1` 이니시스 콜백 승인 → 서버 승인·확정 + app 성공 스킴 302 / `I2` PG 거절 → 주문 READY 유지, web fail 302 / `I3` 인증실패(P_STATUS≠00) → 승인 호출 없이 fail 302 / `I4` 알 수 없는 P_OID(위조) → web fail 302
- `O1` GET /payments/orders/{id} 소유자 조회(DONE·확정) / `O2` 남의 주문 조회 400
- `InicisPaymentTransmissionTest`(K/M/V) — 이니시스 전문·서명·hashData 바이트동일성·SSRF(외부 호출 0, 자격증명 불요)
- `RefundUseCaseTest` `RF4` — PG 스왑 후 과거 주문은 결제 당시 PG(이니시스)로 환불, 새 active(토스)로 안 나간다 / `RF5` 강사 거절→REJECTED+전액 자동환불(cancel 호출·환불기록) / `RF6` 무응답 만료→CANCELLED+환불

enrollment 측 전이는 `EnrollmentUseCaseTest`(A1 수락→CONFIRMED·A2 거절→REJECTED·F1 만석).
