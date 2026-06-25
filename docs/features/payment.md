# 결제 (payment)

> **피처 문서** — 정책·왜·히스토리를 소유. 구현(ER·엔드포인트·흐름)은 [docs/architecture/payment.md](../architecture/payment.md) 로 링크만.

## 한 줄

수강신청이 **강사 수락 → 결제 → 확정**으로 닫히는 마지막 단계. 토스페이먼츠 **결제위젯 v2** 로 연동 — FE 위젯이 결제하고 BE 가 시크릿 키로 승인한다. booking([booking.md](booking.md))이 "강사 답변 대기"까지였다면, payment 는 그 수락을 실제 확정으로 만든다.

## 협력 도메인

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| payment | [payment.md](../architecture/payment.md) | 주문(PaymentOrder)·토스 승인·금액 권위·enrollment 확정 (이 피처) |
| enrollment | [enrollment.md](../architecture/enrollment.md) | 수락 → PAYMENT_PENDING(슬롯 점유) → 결제 후 CONFIRMED |
| course | [course.md](../architecture/course.md) | 라이브 수강료(권위 금액 재계산 입력) |
| venue | [venue.md](../architecture/venue.md) | 입장료(daypart fee) — 신청 스냅샷으로 금액에 포함 |

## 정책 (requirements)

### 수락 → 결제 → 확정 (생명주기)

강사 수락은 더 이상 즉시 확정이 아니다. 수락 = `PAYMENT_PENDING`(결제 대기, **슬롯 점유** — 결제가 끝날 때까지 남이 못 채감). 학생이 결제를 승인해야 `CONFIRMED`. 결제 미완은 v1 에선 무기한 점유(만료/환불 상태기계는 후속).

### 금액은 서버 권위값 (보안 핵심)

클라이언트가 보낸 금액을 **절대 신뢰하지 않는다**. `POST /payments/prepare` 가 서버에서 권위 금액을 재계산해 주문(`PaymentOrder.amount`)에 박고, `confirm` 은 클라이언트가 보낸 amount 가 그 값과 같을 때만 토스 승인을 호출한다(토스도 같은 금액으로 승인 → 위젯 결제액이 다르면 토스가 거절). 이중 방어. 권위 금액 = **코스 라이브 수강료 + 입장료 스냅샷 + 장비 스냅샷**(신청 스냅샷은 "추정치"라 가장 잘 변하는 수강료를 결제 시점 라이브로 다시 읽음).

### 시크릿 키는 BE 밖으로 안 나간다

승인(`/v1/payments/confirm`)은 **서버가 시크릿 키로** 한다(juso 승인키 기조와 동일). FE 엔 `clientKey`(공개값)만 prepare 응답으로 내려가 위젯을 띄운다.

### 멱등

`confirm` 은 멱등 — 이미 DONE 인 주문 재호출(새로고침·재시도)도 200 DONE(이중 승인 없음). 토스 호출엔 `Idempotency-Key = orderId`. prepare 도 멱등(같은 enrollment 의 READY 주문 재사용).

### 로컬 stub / 실연동 분리

로컬·테스트 기본은 stub(토스 미호출·즉시 DONE) — 외부 PG 에 묶이지 않게. staging/prod 만 `PAYMENT_MODE=toss` 로 실연동. address(juso)·identity-verification 과 같은 `@ConditionalOnProperty` interface 교체 패턴.

## 결정 히스토리

| 시점 | 결정 | 왜 |
|---|---|---|
| 2026-06-26 | 토스페이먼츠 **결제위젯 v2** 채택 | 디자인 결제 단계 · 위젯이 결제수단 UI 를 흡수 |
| 2026-06-26 | 수락 = `PAYMENT_PENDING`(점유) → 결제 = `CONFIRMED` | enrollment 풀버전 설계 "수락 → 결제 → 확정"(주석·CLAUDE.md 에 박혀 있던 간극을 채움) |
| 2026-06-26 | 금액 서버 권위값 + 클라 amount 대조 | 클라 변조 방지(PG 연동 표준) |
| 2026-06-26 | **문서용 테스트 키로 개발 + PG 심사** | 전자결제 신청 후 키 발급 전 — 토스가 문서용 키로 개발 허용. 심사는 staging 실 결제 요구([[phase_4_deployment_decisions]]) |
| 2026-06-26 | webhook 이번 PR 제외(confirm 리다이렉트만) | 심사 핵심 경로 우선, 비동기 상태는 후속 |

## 미해결 / 확장

- 🔴 **webhook** — 비동기 상태(가상계좌 입금·취소·부분취소) 수신 + 서명 검증.
- 🟡 **결제 미완 만료·환불 상태기계** — PAYMENT_PENDING 무기한 점유 해소(만료 → 슬롯 해제, 환불 → CANCELED).
- 🟡 **입장료/장비 live 재계산** — 현재 수강료만 라이브, 나머진 신청 스냅샷.
- 🟢 **정산 수수료 분해** — PG 3.4% + 플랫폼 6.6%(enrollment `아직 안 한 것`과 함께).
- 🟢 **캘린더 미결제 별도 표시** — 현재 PAYMENT_PENDING 은 confirmed 점유 버킷에 합산.

## 본인이 직접 처리할 것 (코드 밖)

- staging 배포 + `PAYMENT_MODE=toss`/`TOSS_CLIENT_KEY`/`TOSS_SECRET_KEY` 주입(문서용 테스트 키 → 발급 후 실키). ECS task def / Parameter Store.
- 토스 PG **심사 신청**(실 결제 1건 시연 경로 확보 후).
- FE: 위젯 SDK 로드 + `requestPayment`(successUrl/failUrl) + success 라우트에서 `/payments/confirm` 호출.

## 관련 메모리

- [[phase_4_deployment_decisions]] — Toss PG 심사가 staging 배포를 요구(Phase 4 진입 트리거)
- [[enrollment_domain_concept]] — 신청 시 결제 없음, 수락 후 결제(이 피처가 채운 간극)
- [[address_geocode_domain]] — 동일한 외부 경계 stub/real 교체 패턴
