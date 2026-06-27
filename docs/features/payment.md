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

### 수락 → 결제 → 확정 (생명주기) + pay-first

강사 수락은 즉시 확정이 아니다. 수락 = `PAYMENT_PENDING`(결제 대기, **슬롯 점유**). 학생이 결제를 승인해야 `CONFIRMED`.

**pay-first (2026-06-28)**: 강사는 **결제 이후에** 수영장을 예약한다(옛 "풀 먼저 예약 후 수락"은 학생 미결제 시 강사가 수영장 패널티를 떠안는 구멍이라 폐기). 결제로 돈이 확보된 뒤 강사가 풀을 잡고, 풀부킹 실패면 **전액 무료 환불/일정변경**(학생 무과실). 학생에겐 "결제완료"로만 보이고 별도 상태 없음. → 결제 미완 취소는 **무료**(강사가 풀 안 잡음), 결제 후 취소만 환불정책. (경계 = 결제. [booking.md](booking.md).)

### 회차별 결제 — 수강료 1회차 / 부대비용 회차마다 (2026-06-28 다회차)

다회차 재설계로 결제 단위가 **회차(EnrollmentRound)** 가 된다:
- **수강료** = `Enrollment` 스냅샷(수강 시작 시 `Course.price` 박제·고정) — **1회차 결제에 전액**. 2회차~정규는 수강료 없음.
- **부대비용**(입장료·장비) = 회차별 스냅샷(위치·요일 따라 일정 잡을 때 확정). **EXTRA** 회차 = 부대 + 추가세션비.
- 따라서 1회차 결제 = 수강료 + 1회차 부대, 2회차~ = 부대만, EXTRA = 부대 + 추가세션비.

### 금액은 서버 권위값 (보안 핵심)

클라이언트가 보낸 금액을 **절대 신뢰하지 않는다**. `POST /payments/prepare` 가 서버에서 권위 금액을 재계산해 주문(`PaymentOrder.amount`)에 박고, `confirm` 은 클라이언트 amount 가 그 값과 같을 때만 토스 승인을 호출(토스도 같은 금액 → 위젯 결제액 다르면 거절). 이중 방어. 권위 금액 = **서버 스냅샷 합**(그 회차의 수강료[1회차만·enrollment 스냅샷] + 입장료 + 장비 + 추가세션비). 수강료는 환불 정산이 깔끔하도록 **enrollment 스냅샷으로 고정**(2026-06-28 결정 — 옛 "수강료 결제 시점 라이브 재계산"을 대체).

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
