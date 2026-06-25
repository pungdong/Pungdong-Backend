# CLAUDE.md — payment (결제 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `enrollment`(상태 읽기 + CONFIRMED 확정) · `course`(라이브 수강료) 를 **단방향 참조**. 외부 PG(토스페이먼츠)와의 경계.

## 무엇이 들어있나 — 토스페이먼츠 결제위젯 v2

수강신청 "수락 → 결제 → 확정" 의 결제 단계. FE 위젯이 결제하고 **승인은 BE 가** 시크릿 키로 호출한다.

- **컨트롤러**: `PaymentController` — `POST /payments/prepare`(주문 생성·위젯 구동값), `POST /payments/confirm`(승인 → 신청 확정). 둘 다 학생 인증.
- **서비스**: `PaymentService` — 권위 금액 재계산·멱등 prepare·소유/금액 검증·토스 승인·enrollment 확정. ⚠️ **명시적 빈 이름 `@Service("enrollmentPaymentService")`** — 레거시 `com.diving.pungdong.service.PaymentService`(죽은 예약 플로우)와 단순명이 같아 스캔 충돌하기 때문. 주입은 타입으로.
- **외부 경계**: `TossPaymentClient`(interface) + `RealTossPaymentClient`(실 토스, `@ConditionalOnProperty pungdong.payment.mode=toss`) / `StubTossPaymentClient`(기본값, 즉시 DONE). address(`JusoAddressApiClient`)·identity 와 동일 패턴.
- **엔티티**: `PaymentOrder`(orderId unique·enrollment·**amount(서버 권위 금액)**·orderName·status·paymentKey·method·approvedAt) → `PaymentOrderJpaRepo`. enum `PaymentStatus`(READY/DONE/CANCELED/FAILED).
- **dto/**: `PaymentPrepareRequest`/`Response`, `PaymentConfirmRequest`/`Response`.

레거시 `domain/payment/Payment` 는 **건드리지 않는다**(옛 예약 플로우 전용, PG 필드 없음).

## 핵심 불변식

- **금액은 서버 권위값** — 클라이언트가 보낸 amount 를 신뢰하지 않는다. prepare 가 서버에서 재계산(`코스 라이브 수강료 + 입장료 스냅샷 + 장비 스냅샷`)해 주문에 박고, confirm 은 클라 amount 가 그 값과 같을 때만 토스 승인. 토스도 같은 금액으로 승인 → 위젯 결제액 다르면 거절.
- **결제 완료 = 확정** — 토스 status=DONE 만이 enrollment 를 `PAYMENT_PENDING` → `CONFIRMED` 로 넘긴다.
- **시크릿 키는 BE 밖으로 안 나간다** — 승인 Basic 인증용. FE 엔 `clientKey`(공개)만 prepare 응답으로.
- **멱등** — confirm 재호출(이미 DONE)도 200 DONE. prepare 는 READY 주문 재사용. 토스엔 `Idempotency-Key=orderId`.

## 보안 매처

`/payments/**` → authenticated (`global/security/SecurityConfiguration`). 소유/상태 게이트는 서비스(비소유/없음=400 존재 숨김, 결제대기 아님=400).

## 설정

`pungdong.payment.mode`(stub|toss) + `toss.secret-key`/`client-key` — `application.yml`·`.env.example`. 로컬 stub 기본(토스 미호출). 키 발급 전 토스 **문서용 테스트 키** 사용 가능(`.env.example` 주석).

## 작업 전 반드시 읽기

- **[docs/features/payment.md](../../../../../../../docs/features/payment.md)** — 정책·왜·히스토리. **여기부터.**
- **[docs/architecture/payment.md](../../../../../../../docs/architecture/payment.md)** — 흐름/ER/권한 매트릭스/간극
- **[enrollment/CLAUDE.md](../enrollment/CLAUDE.md)** — 수락→결제대기→확정 생명주기
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 안전망 테스트

`src/test/.../usecase/PaymentUseCaseTest` — 실 H2 + 시큐리티 체인, `TossPaymentClient` 만 `@MockBean`. P1(prepare)·P2(confirm→확정)·P3(금액불일치)·P4(멱등)·P5(결제대기 아님)·P6(비소유)·P7(점유→둘째 수락 차단). ⚠️ Authorization raw JWT.

## 아직 안 한 것 (후속 PR)

- **webhook** — 비동기 상태(가상계좌·취소) + 서명 검증.
- **결제 미완 만료·환불 상태기계** — PAYMENT_PENDING 무기한 점유 해소.
- **입장료/장비 live 재계산** · **정산 수수료 분해**(PG 3.4% + 플랫폼 6.6%).
- REST Docs `document(...)`(use-case 로 대체).
