# CLAUDE.md — payment (결제 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `enrollment`(상태 읽기 + CONFIRMED 확정) · `course`(라이브 수강료) 를 **단방향 참조**. 외부 PG 와의 경계 — **PG 중립**(토스페이먼츠 / KG이니시스 표준결제 교체 가능, `PAYMENT_MODE` 로 플러그식 스왑).

## 무엇이 들어있나 — PG 중립 결제(토스 위젯 v2 / 이니시스 표준결제)

수강신청의 결제 단계. **선결제(2026-08-07 도입 → 2026-08-09 전 회차 통일)** — 회차 구분 없이 "신청 → 즉시 결제(ACCEPT_PENDING) → 강사 수락(CONFIRMED)/거절·만료(자동환불)". FE 위젯이 결제하고 **승인은 BE 가** 시크릿 키로 호출한다.

- **컨트롤러**: `PaymentController` — `POST /payments/prepare`·`confirm`(TOSS/STUB, 학생 인증)·**`GET /payments/orders/{orderId}`**(상세조회, 소유권). **`InicisReturnController`** — **`POST /payments/inicis/return`(permitAll)** = 이니시스 결제창 form POST 콜백 → 세션리스 승인 → 302 리다이렉트(주문 `client` 로 web/app). ⚠️ 앱 WebView 가 POST 본문을 못 읽어 이니시스는 confirm 주체가 BE. **`RefundController`** — `POST /enrollments/{enrollmentId}/refund`(수강 종료=남은 회차 환불; enrollment 경로지만 PG 취소라 payment 패키지 — enrollment→payment 역참조 방지).
- **서비스**: `PaymentService`(권위 금액·멱등 prepare·PG 승인·회차 확정. ⚠️ **빈 이름 `@Service("enrollmentPaymentService")`** — 레거시 단순명 충돌 회피). **`RefundService`**(수강 종료 — `RefundCalculator` 산정 + 주문별 PG 부분취소 + 회차 CANCELLED + 좌석 해제). **`RefundCalculator`**(회차별 환불 정책: done=0·미배정=수강료/N·배정취소=(수강료/N+부대)×율; **수강료 몫은 1회차 주문**, 부대는 각 회차 주문).
- **외부 경계**: **`PaymentGateway`**(interface — `provider`·`initParams`·`confirm`·**`cancel`(부분취소)**) + 구현 3개:
  - `StubPaymentGateway`(기본값, 외부 미호출·즉시 승인) / `TossPaymentGateway`(`mode=toss`) / `InicisPaymentGateway`(`mode=inicis`).
  - **PG 어휘는 어댑터 안에 가둔다** — `ConfirmResult.approved` 가 PG별 성공표현(토스 `DONE` / 이니시스 `P_STATUS=00`)을 정규화하고, `pgTransactionId`(토스 `paymentKey` / 이니시스 `P_TID`)로 취소 식별자를 통일. 서비스는 PG 를 모른다.
  - ⚠️ `PaymentOrder.paymentKey` 컬럼은 이름만 토스 유래 — 실제로는 **PG 거래 식별자**(이니시스면 `P_TID`)를 담는다. 컬럼 리네임은 Flyway 마이그레이션이 붙어 미뤘다.
- **`PaymentGatewayRegistry`** — 어느 PG 로 보낼지 고른다. 어댑터는 `@ConditionalOnProperty` 없이 **전부 빈**으로 뜨고, 레지스트리가 선택: `active()`=전역 설정(신규 결제) / `forOrder(order.provider)`=주문에 박제된 PG(기존 주문 승인·환불). **왜**: PG 를 갈아탄 뒤 과거 주문 환불이 엉뚱한 PG 로 나가면 "돈은 받고 환불 실패"(FE 리뷰). `PaymentOrder.provider` 는 prepare 가 박고(V10), 승인·환불은 그 값으로 라우팅.
  - **이니시스 특이점**(INIpay PRO, P_ 스킴): `initParams` 는 **외부 호출 없이** P_ 파라미터+서명 `P_CHKFAKE`(=Base64(SHA-512(P_AMT+P_OID+P_TIMESTAMP+hashKey)))만 계산 — FE 가 `INIPayPro_v2.js` 로 결제창을 띄운다. **승인(payAppl.ini)엔 서명이 없다** → 금액은 콜백값이 아니라 주문 권위값으로 대조. **승인 호스트를 콜백 `P_IDCNAME`으로 조립**하므로 `idcHost()`가 소문자 토큰만 허용(SSRF 방어). **환불(iniapi V2 JSON)** 의 `hashData`(SHA-512hex)는 body 의 `data`와 **바이트 동일**해야 통과 → `data`를 한 번만 직렬화해 양쪽에 쓴다. 부분취소 `confirmPrice`=취소 **후** 잔액(포트 `remainingAmount`−`cancelAmount`). 테스트/운영은 엔드포인트가 아니라 **MID**(테스트 `INIpayTest`)로 갈려 live 플래그 없음.
- **엔티티**: `PaymentOrder`(orderId·**enrollmentRound**·amount(권위 금액)·status·paymentKey…) → `PaymentOrderJpaRepo`. **`RefundOrder`**(paymentOrder·amount·reason·status — 주문별 환불 감사기록) → `RefundOrderJpaRepo`. enum `PaymentStatus`(READY/DONE/CANCELED/FAILED), `RefundStatus`(REQUESTED/DONE/FAILED).
- **dto/**: `PaymentPrepare/Confirm Request/Response`(+**`orderNo`** = CS·고객용 주문번호), **`RefundQuote`**(total + 회차별 line: tuitionPart/extraPart 분리 — 실행 매핑용).
- **`OrderNoFormatter`**: 순차 `PaymentOrder` id → **Hashids 난독화 코드**(`PD-YYMMDD-XXXXXXXX`, 날짜+가역·혼동문자 제외). PG `orderId`(멱등키, 내부)와 별개의 표시값 — 누적 주문 수 유추 방지. salt=`pungdong.hashids.salt`(키, 노출 금지). ⚠️ account/course 등 **다른 외부 id 난독화**는 별도 "공개 식별자 전략" 안건(아직 X).

레거시 `domain/payment/Payment` 는 **건드리지 않는다**(옛 예약 플로우 전용, PG 필드 없음).

## 핵심 불변식

- **금액은 서버 권위값** — 클라이언트가 보낸 amount 를 신뢰하지 않는다. prepare 가 서버에서 재계산(`코스 라이브 수강료 + 입장료 스냅샷 + 장비 스냅샷`)해 주문에 박고, confirm 은 클라 amount 가 그 값과 같을 때만 PG 승인. **PG 에도 주문에 박힌 금액**을 보내므로 결제창 결제액이 다르면 PG 가 거절.
- **결제 완료 = 전이** — `ConfirmResult.approved` 만이 enrollment 를 다음 상태로: **전 회차 `PENDING → ACCEPT_PENDING`**(강사 결정 대기). 강사 거절·학생 취소·무응답 만료 시 enrollment 이벤트(`EnrollmentRefundRequestedEvent`)를 `EnrollmentRefundListener` 가 받아 `RefundService.refundRoundFully` 로 **전액 자동환불**(동기·롤백안전, payment→enrollment 방향). 결제 후 슬롯이 더 싼 자리로 바뀌면 `EnrollmentPartialRefundRequestedEvent` → `refundRoundPartially` 로 **차액만** 환불(같은 계약).
- **시크릿 키는 BE 밖으로 안 나간다** — 승인 Basic 인증용. FE 엔 `clientKey`(공개)만 prepare 응답으로.
- **멱등** — confirm 재호출(이미 DONE)도 200 DONE. prepare 는 READY 주문 재사용. 토스엔 `Idempotency-Key=orderId`(이니시스는 콜백 승인이 주문 상태로 멱등 — 이미 DONE 이면 재승인 안 함).

## 보안 매처

`/payments/**` → authenticated (`global/security/SecurityConfiguration`) — **단 `POST /payments/inicis/return` 은 그 앞에서 permitAll**(콜백엔 우리 JWT 없음; 인증은 `P_AUTH_TID` — 우리 콜백에만 옴). 소유/상태 게이트는 서비스(비소유/없음=400 존재 숨김, 결제대기 아님=400). 이니시스 세션리스 승인은 소유권 대신 P_AUTH_TID + 서버 권위 금액 대조가 방어.

## 설정

`pungdong.payment.mode`(**stub|toss|inicis**, 부팅 시 하나만) + `toss.secret-key`/`client-key` + `inicis.mid`/`hash-key`(P_CHKFAKE 서명)/`api-key`(환불 hashData)/`client-ip`(환불 전문 IP)/`ret-url`(=**BE 콜백 URL** `.../payments/inicis/return`)/`return-web-success|fail`(환경별)/`return-app-success|fail`(기본 `plop://payment/...`) — `application.yml`·`.env.example`. 로컬 stub 기본(외부 미호출). 이니시스 테스트/운영은 엔드포인트가 아니라 **MID**(테스트 `INIpayTest`)로 갈려 live 플래그 없음. 키 발급 전 토스 **문서용 테스트 키**도 사용 가능(`.env.example` 주석).

⚠️ **`PAYMENT_MODE` 스왑은 배포와 묶인 값**(런타임 토글 아님): mode 가 배포 이미지에 없는 `PaymentProvider` enum 을 가리키면 `PaymentGatewayRegistry` 가 부팅 실패로 **앱 전체가 안 뜬다**. 이미지가 enum 가진 뒤에만 flip, 롤백은 역순, SSM 시크릿 선행 — 스왑 런북은 [docs/architecture/deployment.md](../../../../../../../docs/architecture/deployment.md) "PG 스왑 / PAYMENT_MODE 변경".

## 작업 전 반드시 읽기

- **[docs/features/payment.md](../../../../../../../docs/features/payment.md)** — 정책·왜·히스토리. **여기부터.**
- **[docs/architecture/payment.md](../../../../../../../docs/architecture/payment.md)** — 흐름/ER/권한 매트릭스/간극
- **[enrollment/CLAUDE.md](../enrollment/CLAUDE.md)** — 수락→결제대기→확정 생명주기
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 안전망 테스트

`src/test/.../usecase/PaymentUseCaseTest` — 실 H2 + 시큐리티 체인, `PaymentGateway` 만 `@MockBean`(PG 중립 사양).
`src/test/.../payment/InicisPaymentTransmissionTest` — 이니시스 전문 사양(외부 호출 0): 승인 전문이 서버 권위 금액을 싣는지(K1), P_CHKFAKE 서명 공식(K2), 환불 hashData `data` 바이트동일성(K3/K4), 부분/전체취소 분기(K5), SSRF idcHost 방어(V2). `PaymentUseCaseTest`: P1(prepare)·P2(confirm→확정)·P3(금액불일치)·P4(멱등)·P5(결제대기 아님)·P6(비소유)·P7(점유→둘째 수락 차단) + **I1~I4 이니시스 콜백**(승인·거절·인증실패·위조) + O1~O2. ⚠️ Authorization raw JWT.

## 아직 안 한 것 (후속 PR)

- **webhook** — 비동기 상태(가상계좌·취소) + 서명 검증.
- ~~결제 미완 만료~~(좌석 lock TTL 로 구현) · ~~환불~~(`RefundService`/`RefundCalculator` 구현 — 수강 종료 부분취소). 환불 **webhook**(부분취소 비동기 수신)·**정산 연계**는 후속.
- **입장료/장비 live 재계산** · **정산 수수료 분해**(PG 3.4% + 플랫폼 6.6%).
- REST Docs `document(...)`(use-case 로 대체).
