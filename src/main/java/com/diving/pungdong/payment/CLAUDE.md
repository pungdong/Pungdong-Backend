# CLAUDE.md — payment (결제 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `enrollment`(상태 읽기 + CONFIRMED 확정) · `course`(라이브 수강료) 를 **단방향 참조**. 외부 PG 와의 경계 — **PG 중립**(토스페이먼츠 / NHN KCP 표준결제 교체 가능).

## 무엇이 들어있나 — PG 중립 결제(토스 위젯 v2 / KCP 표준결제)

수강신청 "수락 → 결제 → 확정" 의 결제 단계. FE 위젯이 결제하고 **승인은 BE 가** 시크릿 키로 호출한다.

- **컨트롤러**: `PaymentController` — `POST /payments/prepare`·`confirm`(둘 다 학생 인증). **`RefundController`** — `POST /enrollments/{enrollmentId}/refund`(수강 종료=남은 회차 환불; enrollment 경로지만 PG 취소라 payment 패키지 — enrollment→payment 역참조 방지).
- **서비스**: `PaymentService`(권위 금액·멱등 prepare·PG 승인·회차 확정. ⚠️ **빈 이름 `@Service("enrollmentPaymentService")`** — 레거시 단순명 충돌 회피). **`RefundService`**(수강 종료 — `RefundCalculator` 산정 + 주문별 PG 부분취소 + 회차 CANCELLED + 좌석 해제). **`RefundCalculator`**(회차별 환불 정책: done=0·미배정=수강료/N·배정취소=(수강료/N+부대)×율; **수강료 몫은 1회차 주문**, 부대는 각 회차 주문).
- **외부 경계**: **`PaymentGateway`**(interface — `provider`·`initParams`·`confirm`·**`cancel`(부분취소)**) + 구현 3개:
  - `StubPaymentGateway`(기본값, 외부 미호출·즉시 승인) / `TossPaymentGateway`(`mode=toss`) / `KcpPaymentGateway`(`mode=kcp`).
  - **PG 어휘는 어댑터 안에 가둔다** — `ConfirmResult.approved` 가 PG별 성공표현(토스 `DONE` / KCP `res_cd=0000`)을 정규화하고, `pgTransactionId`(토스 `paymentKey` / KCP `tno`)로 취소 식별자를 통일. 서비스는 PG 를 모른다.
  - ⚠️ `PaymentOrder.paymentKey` 컬럼은 이름만 토스 유래 — 실제로는 **PG 거래 식별자**(KCP 면 `tno`)를 담는다. 컬럼 리네임은 Flyway 마이그레이션이 붙어 미뤘다.
  - **KCP 특이점**: 모바일만 거래등록(`initParams` 안에서 외부 호출), PC 는 JS SDK. 취소엔 **RSA 서명**(개인키) 필요. `Ret_URL` 은 서버 고정(오픈 리다이렉트 방지). 응답 결제수단은 카드형/머니형/포인트형 **3갈래**라 `methodLabel()` 로 정규화하고, **금액은 응답에서 안 읽는다**(쿠폰·포인트 100% 결제 시 `card_mny=0`).
- **엔티티**: `PaymentOrder`(orderId·**enrollmentRound**·amount(권위 금액)·status·paymentKey…) → `PaymentOrderJpaRepo`. **`RefundOrder`**(paymentOrder·amount·reason·status — 주문별 환불 감사기록) → `RefundOrderJpaRepo`. enum `PaymentStatus`(READY/DONE/CANCELED/FAILED), `RefundStatus`(REQUESTED/DONE/FAILED).
- **dto/**: `PaymentPrepare/Confirm Request/Response`(+**`orderNo`** = CS·고객용 주문번호), **`RefundQuote`**(total + 회차별 line: tuitionPart/extraPart 분리 — 실행 매핑용).
- **`OrderNoFormatter`**: 순차 `PaymentOrder` id → **Hashids 난독화 코드**(`PD-XXXXXXXX`, 가역·혼동문자 제외). PG `orderId`(멱등키, 내부)와 별개의 표시값 — 누적 주문 수 유추 방지. salt=`pungdong.hashids.salt`(키, 노출 금지). ⚠️ account/course 등 **다른 외부 id 난독화**는 별도 "공개 식별자 전략" 안건(아직 X).

레거시 `domain/payment/Payment` 는 **건드리지 않는다**(옛 예약 플로우 전용, PG 필드 없음).

## 핵심 불변식

- **금액은 서버 권위값** — 클라이언트가 보낸 amount 를 신뢰하지 않는다. prepare 가 서버에서 재계산(`코스 라이브 수강료 + 입장료 스냅샷 + 장비 스냅샷`)해 주문에 박고, confirm 은 클라 amount 가 그 값과 같을 때만 PG 승인. **PG 에도 주문에 박힌 금액**을 보내므로 결제창 결제액이 다르면 PG 가 거절.
- **결제 완료 = 확정** — `ConfirmResult.approved` 만이 enrollment 를 `PAYMENT_PENDING` → `CONFIRMED` 로 넘긴다.
- **시크릿 키는 BE 밖으로 안 나간다** — 승인 Basic 인증용. FE 엔 `clientKey`(공개)만 prepare 응답으로.
- **멱등** — confirm 재호출(이미 DONE)도 200 DONE. prepare 는 READY 주문 재사용. 토스엔 `Idempotency-Key=orderId`(KCP 는 `ordr_no`).

## 보안 매처

`/payments/**` → authenticated (`global/security/SecurityConfiguration`). 소유/상태 게이트는 서비스(비소유/없음=400 존재 숨김, 결제대기 아님=400).

## 설정

`pungdong.payment.mode`(**stub|toss|kcp**, 부팅 시 하나만) + `toss.secret-key`/`client-key` + `kcp.site-cd`/`cert-info`/`private-key`(+`-password`)/`ret-url`/`live` — `application.yml`·`.env.example`. 로컬 stub 기본(외부 미호출). 키 발급 전 토스 **문서용 테스트 키** 사용 가능(`.env.example` 주석).

## 작업 전 반드시 읽기

- **[docs/features/payment.md](../../../../../../../docs/features/payment.md)** — 정책·왜·히스토리. **여기부터.**
- **[docs/architecture/payment.md](../../../../../../../docs/architecture/payment.md)** — 흐름/ER/권한 매트릭스/간극
- **[enrollment/CLAUDE.md](../enrollment/CLAUDE.md)** — 수락→결제대기→확정 생명주기
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 안전망 테스트

`src/test/.../usecase/PaymentUseCaseTest` — 실 H2 + 시큐리티 체인, `PaymentGateway` 만 `@MockBean`(PG 중립 사양).
`src/test/.../payment/KcpPaymentTransmissionTest` — KCP 전문 사양(외부 호출 0): 승인 전문이 서버 권위 금액을 싣는지, 부분/전체취소 분기, 금지문자 제거, 결제수단 3갈래. P1(prepare)·P2(confirm→확정)·P3(금액불일치)·P4(멱등)·P5(결제대기 아님)·P6(비소유)·P7(점유→둘째 수락 차단). ⚠️ Authorization raw JWT.

## 아직 안 한 것 (후속 PR)

- **webhook** — 비동기 상태(가상계좌·취소) + 서명 검증.
- ~~결제 미완 만료~~(좌석 lock TTL 로 구현) · ~~환불~~(`RefundService`/`RefundCalculator` 구현 — 수강 종료 부분취소). 환불 **webhook**(부분취소 비동기 수신)·**정산 연계**는 후속.
- **입장료/장비 live 재계산** · **정산 수수료 분해**(PG 3.4% + 플랫폼 6.6%).
- REST Docs `document(...)`(use-case 로 대체).
