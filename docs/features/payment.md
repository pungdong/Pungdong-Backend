# 결제 (payment)

> **피처 문서** — 정책·왜·히스토리를 소유. 구현(ER·엔드포인트·흐름)은 [docs/architecture/payment.md](../architecture/payment.md) 로 링크만.

## 한 줄

수강신청의 **결제** 단계. **선결제(2026-08-07 도입 → 2026-08-09 전 회차 통일)** — 회차 구분 없이 **신청 즉시 결제**하고(장바구니 없이 "주문→결제"), 강사는 그 뒤 수락/거절/일정조정 제안만 한다(거절·무응답 시 자동환불). **PG 중립** — FE 결제창이 결제하고 BE 가 승인한다. 실제 PG(토스페이먼츠 결제위젯 v2 / KG이니시스 INIpay PRO 표준결제)는 `PAYMENT_MODE` 로 교체한다(플러그식 스왑).

## 협력 도메인

| 도메인 | 구현 문서 | 역할 |
|---|---|---|
| payment | [payment.md](../architecture/payment.md) | 주문(PaymentOrder)·PG 승인·금액 권위·enrollment 확정 (이 피처) |
| enrollment | [enrollment.md](../architecture/enrollment.md) | (선결제 1회차) 신청 PENDING → 결제 후 ACCEPT_PENDING → 강사 수락 CONFIRMED / 거절·만료 자동환불 |
| course | [course.md](../architecture/course.md) | 라이브 수강료(권위 금액 재계산 입력) |
| venue | [venue.md](../architecture/venue.md) | 입장료(daypart fee) — 신청 스냅샷으로 금액에 포함 |

## 정책 (requirements)

### 선결제 (신청 → 즉시 결제 → 강사 수락/거절) — 2026-08-07

**1회차는 신청과 동시에 결제한다.** 신청 = `PENDING`(미결제·**좌석 점유**) → 학생이 곧바로 결제 → `ACCEPT_PENDING`(결제완료·강사 확인 대기) → 강사 수락 `CONFIRMED` / 강사 거절·무응답 24h `REJECTED`·`CANCELLED` + **전액 자동환불**. 미결제로 방치하면 12h 뒤 좌석 해제(환불 없음).

**왜 신청 시점으로 앞당겼나**: ① 제품상 어차피 붙일 방향. ② **카드사 심사 리스크↓** — "신청 따로 / 결제 따로"는 카드사에 결제 흐름을 별도로 설명해야 하는 비표준 구조. "주문 즉시 결제"가 카드사가 익숙한 표준이라 심사가 깔끔. 어차피 할 거면 심사 전에 표준 흐름으로 바꿔 받는 게 유리. 결제·환불 인프라는 이미 있고 실카드 왕복 검증 완료(2026-08-07)라 거절→자동환불은 검증된 코드 재사용.

**용어 정정**: 옛 "pay-first(2026-06-28)"는 *"강사가 수영장을 결제 이후에 예약한다"*(= 풀부킹이 결제 뒤)는 뜻이었다. 그 통찰(돈 확보 후 풀 잡기, 풀부킹 실패 시 전액 무료 환불)은 그대로 유효하고, 이제 결제 시점이 **강사 수락보다도 앞(신청 시점)**으로 더 당겨진 것. 결제 경계는 여전히 유효.

**동시성**: 선결제라 오버부킹 = 이중결제이므로 좌석 경합을 원천 차단(비관적 락 + 세션 자연키 UNIQUE). 상세는 [enrollment.md](../architecture/enrollment.md) §3-2.

### 회차별 결제 — 수강료 1회차 / 부대비용 회차마다 (2026-06-28 다회차)

다회차 재설계로 결제 단위가 **회차(EnrollmentRound)** 가 된다:
- **수강료** = `Enrollment` 스냅샷(수강 시작 시 `Course.price` 박제·고정) — **1회차 결제에 전액**. 2회차~정규는 수강료 없음.
- **부대비용**(입장료·장비) = 회차별 스냅샷(위치·요일 따라 일정 잡을 때 확정). **EXTRA** 회차 = 부대 + 추가세션비.
- 따라서 1회차 결제 = 수강료 + 1회차 부대, 2회차~ = 부대만, EXTRA = 부대 + 추가세션비.

### 금액은 서버 권위값 (보안 핵심)

클라이언트가 보낸 금액을 **절대 신뢰하지 않는다**. `POST /payments/prepare` 가 서버에서 권위 금액을 재계산해 주문(`PaymentOrder.amount`)에 박고, `confirm` 은 클라이언트 amount 가 그 값과 같을 때만 PG 승인을 호출(PG 에도 **주문에 박힌 금액**을 보내므로 결제창 결제액과 다르면 PG 가 거절). 이중 방어. 권위 금액 = **서버 스냅샷 합**(그 회차의 수강료[1회차만·enrollment 스냅샷] + 입장료 + 장비 + 추가세션비). 수강료는 환불 정산이 깔끔하도록 **enrollment 스냅샷으로 고정**(2026-06-28 결정 — 옛 "수강료 결제 시점 라이브 재계산"을 대체).

### 시크릿 키는 BE 밖으로 안 나간다

승인(`/v1/payments/confirm`)은 **서버가 시크릿 키로** 한다(juso 승인키 기조와 동일). FE 엔 `clientKey`(공개값)만 prepare 응답으로 내려가 위젯을 띄운다.

### 멱등

`confirm` 은 멱등 — 이미 DONE 인 주문 재호출(새로고침·재시도)도 200 DONE(이중 승인 없음). 토스 호출엔 `Idempotency-Key = orderId`(이니시스는 콜백 승인이 주문 상태로 멱등 — 이미 DONE 이면 재승인 안 함). prepare 도 멱등(같은 회차의 READY 주문 재사용).

### 로컬 stub / 실연동 분리

로컬·테스트 기본은 stub(외부 미호출·즉시 승인) — 외부 PG 에 묶이지 않게. staging/prod 만 `PAYMENT_MODE=toss|inicis` 로 실연동(부팅 시 하나만). address(juso)·identity-verification 과 같은 interface 교체 패턴이되, 선택은 `PaymentGatewayRegistry` 가 한다(어댑터는 전부 빈으로 등록).

## 결정 히스토리

| 시점 | 결정 | 왜 |
|---|---|---|
| 2026-06-26 | 토스페이먼츠 **결제위젯 v2** 채택 | 디자인 결제 단계 · 위젯이 결제수단 UI 를 흡수 |
| 2026-06-26 | ~~수락 = `PAYMENT_PENDING`(점유) → 결제 = `CONFIRMED`~~ (2026-08-09 폐기) | enrollment 풀버전 설계 "수락 → 결제 → 확정"(주석·CLAUDE.md 에 박혀 있던 간극을 채움) |
| 2026-06-26 | 금액 서버 권위값 + 클라 amount 대조 | 클라 변조 방지(PG 연동 표준) |
| 2026-06-26 | **문서용 테스트 키로 개발 + PG 심사** | 전자결제 신청 후 키 발급 전 — 토스가 문서용 키로 개발 허용. 심사는 staging 실 결제 요구([[phase_4_deployment_decisions]]) |
| 2026-06-26 | webhook 이번 PR 제외(confirm 리다이렉트만) | 심사 핵심 경로 우선, 비동기 상태는 후속 |
| 2026-06-28 | **가상계좌(무통장) 안 받음 + 웹훅 MVP 보류** | 예약형 UX = 즉시 좌석 lock 인데 가상계좌는 나중 입금이라 근본 충돌(자리 잡고 미입금 = 소규모 정원에서 남 자리 막음). 가상계좌만이 웹훅을 *필수*로 만드는 항목(입금이 웹훅으로 와야 확정) — 안 받으면 confirm 동기로 충분, **환불도 동기**(토스 cancel 즉시)라 웹훅 불필요. 수수료는 가상계좌가 최저(~1% vs 카드 2.x~3.4%)지만 출시 초 거래량에선 절대액 미미 → 복잡도 대비 안 남음. **위젯에서 가상계좌 OFF**(토스 `paymentMethods`/머지 콘솔, FE·운영 작업·BE 변경 0). |
| 2026-06-28 | 웹훅은 **거래량↑·가상계좌 재검토 시** 별도 포커스 PR | 비동기 결제(가상계좌 입금)·out-of-band 취소·정산 reconciliation 용. 그때 **기존 12h 결제 TTL** 을 "가상계좌도 12h 내 입금 안 하면 좌석 해제"로 재사용 → lock+입금기한+웹훅이 자연스럽게 붙음(모델은 이미 준비). |
| 2026-06-28 | **CS·고객용 주문번호 `orderNo`(Hashids)** — 토스 `orderId`(멱등키)와 분리 | 토스 orderId(`rnd-1-uuid`)는 부르기 어렵고 순차 노출은 누적 주문 수 유추. `OrderNoFormatter` 가 auto-increment id → 가역 코드(`PD-XXXXXXXX`). auto-increment=동시성/유일성 DB 보장(유저값 불필요), Hashids=비순차 난독화. account/course 등 **다른 외부 id 난독화는 "공개 식별자 전략" 별도 안건**(enumeration 방지, 미진행). |
| 2026-07-25 | **KCP 표준결제 병행 연동 + 결제 포트 PG 중립화** | 토스 **심사 시작까지만 3개월** 대기 통보 — 8월 런칭을 막는 블로커. 토스는 심사에 그대로 태워두고 KCP 를 병행. PG 를 **직연동**(포트원 등 아그리게이터 미채택)한 이유: 이미 `PaymentGateway` 포트가 있어 어댑터 1개면 되고, 필요한 전환이 "주문별 라우팅"이 아니라 **앱 전체 flip**(config 한 줄)이라 아그리게이터의 값이 안 붙음. 포트원은 본인확인에만 계속 사용. |
| 2026-07-27 | **KCP 만 confirm 주체 = BE**(Ret_URL=BE 콜백)(FE 핑퐁 #1~#3) | 앱 WebView 가 결제창의 form POST 본문을 못 읽음(`onShouldStartLoadWithRequest` 는 GET 만). 그래서 KCP 는 Ret_URL 을 BE(`/payments/kcp/return`, permitAll)로 두고 서버가 승인 후 GET 리다이렉트 → 웹·앱 통일. 리다이렉트 타겟은 주문에 박제한 `client`(web/app, V11)로 고정 allowlist 선택(오픈리다이렉트 방지). 세션리스 승인은 KCP 암호데이터가 인증. TOSS/STUB 는 FE confirm 유지. + `GET /payments/orders/{id}`(성공화면·재진입 조회). |
| 2026-07-25 | **provider 를 주문에 박제 + 라우팅 이원화**(FE 리뷰) | 전역 설정은 *신규* 주문의 PG 만 정한다. 승인·환불은 주문에 저장된 `PaymentOrder.provider` 로 라우팅(`PaymentGatewayRegistry.forOrder`). 안 그러면 KCP→토스 전환 후 과거 KCP 주문 환불이 토스로 나가 **돈은 받고 환불 실패**. 어댑터는 `@ConditionalOnProperty` 를 떼고 전부 빈으로 등록(옛 PG 로도 취소 가능해야 하므로). V10 마이그레이션 + `RF4` 테스트. |
| 2026-07-25 | prepare 요청에 **`roundId`** 추가(`enrollmentId` deprecated 병행)(FE 리뷰) | 결제 단위는 회차인데 옛 필드명 `enrollmentId` 가 회차 id 를 담아, 수강 id 를 쓰는 환불 path 와 이름이 겹쳐 위험. 둘 다 number 라 타입으로 안 잡힘. |
| 2026-07-25 | KCP 는 **LITE PAY 아닌 표준결제** | LITE PAY 가 리다이렉트 통일·SDK 불필요로 더 단순했지만, **간편결제(카카오·네이버·토스페이 등)가 표준 결제창에만 노출**되고(공식 문서 명시) KCP 공식 MCP 문서도 표준결제 라인만 커버. 간편결제 상실 + 문서지원 상실 대비 FE 단순화 이득이 안 남음. |
| 2026-07-29 | **KCP 폐기 → KG이니시스로 전환** | KCP 가 학생→플랫폼 결제 후 강사 정산 구조를 **"중개 플랫폼 미지원"으로 온보딩 거절**. 이니시스는 중개서비스(지급대행) 공식 지원 → 전환. PG중립 구조라 **어댑터 하나 교체**(KCP 코드 삭제, `provider:INICIS`). 되살릴 일 없어 KCP 는 fallback 아님(이론상 fallback 은 토스). [[payment_pg_kcp_switch]] |
| 2026-08 | **이니시스 INIpay PRO 어댑터 + KCP 제거**(이 PR) | INIpay PRO 표준결제(P_ 스킴, `INIPayPro_v2.js`). 흐름은 KCP 와 동일(P_NEXT_URL=BE 콜백 → 서버승인 → 302). **카드+간편결제만**(P_PAY_TYPE=CARD, 가상계좌·계좌이체 제외 → 입금통보 webhook 회피). 승인(payAppl.ini)엔 서명이 없어 **금액 서버권위 대조 + P_IDCNAME allowlist(SSRF)** 가 방어. 환불 hashData 는 body 의 data 와 **바이트 동일**해야 통과. 테스트/운영은 엔드포인트 아닌 **MID**(INIpayTest)로 갈려 live 플래그 없음. **토스·이니시스 공존**(PAYMENT_MODE 로 플러그식 스왑, forOrder 로 과거 주문 원 PG 환불). |
| 2026-08-07 | **이니시스 결제+환불 실 왕복 검증 완료**(staging 테스트 MID) | 실카드로 결제창→승인→DONE→CONFIRMED→환불(iniapi)→CANCELLED 전 사이클 성공 + **카드 승인·취소 문자 수신**. 확정: 환불 tid 필드 OK · **clientIp 등록 불요 → fck-nat 불필요** · hashData 바이트동일성 · 전액취소 정상. 결제 감사로그(성공 경로) 추가. prod flip(카드사심사)이 다음. |
| 2026-08-07 | **선결제 전환**(1회차): 신청 즉시 결제 → 강사 수락/거절(자동환불). 신규 상태 `ACCEPT_PENDING` | ① 어차피 붙일 방향 ② **카드사 심사 리스크↓** — "신청 따로/결제 따로"는 카드사에 흐름을 설명해야 하는 비표준. "주문 즉시 결제"가 표준이라 심사 전에 바꿔 받는 게 유리. 거절→자동환불은 검증된 PG cancel 재사용(이벤트→listener→`refundRoundFully`, 동기·롤백안전). 오버부킹=이중결제라 좌석 경합 하드닝(비관락+UNIQUE, V12). 스코프=1회차(2회차+ 는 구 흐름 유지). prod flip(inicis+선결제 함께)이 다음. |
| 2026-08-10 | **더 비싼 슬롯 = 차액만 추가 결제**(V16) — 목표 슬롯을 주문에 싣고 승인 순간 교체 + **강사 재수락 대기**, 결제창 동안 좌석 hold, 미결제면 주문만 만료 | 400 으로 막고 "취소→재신청"으로 우회시키면 **전액 환불 후 전액 재결제**라 30만원대 강의에서 돈이 며칠 묶인다. 대기를 예약이 아니라 **주문**에 두면 방금 없앤 `PAYMENT_PENDING` 류를 되살리지 않고도 결제 대기를 표현할 수 있다 — 회차 상태는 내내 그대로고, 포기하면 주문만 만료돼 롤백할 것이 없다. 강사 제안도 더 비싼 슬롯을 낼 수 있게 필터 완화. ★ 학생이 임의로 고른 시간은 강사 동의가 없으므로 **결제 후에도 강사 수락이 필요**하다(거절 시 차액 포함 전액 환불) — 재수락이 없는 건 강사가 낸 제안을 고르는 pick-slot 뿐 |
| 2026-08-10 | **환불 집계를 회차 단위로** — 한 회차에 승인 주문 N개 허용(`paidOrders` List), 회차 전액 환불은 주문별 루프, 차액 환불은 최신 주문부터 차감 | `paidOrder` 가 `Optional` 이라 **회차에 승인 주문이 2건 생기는 순간 환불 경로가 예외로 터졌다** — 차액 추가 결제(#204)의 하드 블로커. 회차 순액 = Σ(승인액)−Σ(환불액) 으로 집계를 올리면서, 실행은 주문 단위(PG 취소 전문이 그 주문 tid 를 요구)라는 구분을 코드에 명시 |
| 2026-08-10 | **환불을 "시도 원장"으로** — PG 호출 직전 `REQUESTED` 선기록(별도 트랜잭션 `REQUIRES_NEW`) → `DONE`/`FAILED` 확정, 실패 진단정보(PG code/msg) 보존, 결과 미확인(`REQUESTED` 잔존) 주문은 자동환불 잠금(V15) | 성공만 기록하면 ① 재시도 근거가 없고 ② PG 원장 대사가 불가능하며 ③ **"PG 엔 취소됐는데 우리 DB 엔 없는" 부분실패를 탐지 못 한다**. 결제는 정석과 기록이 가장 중요하다는 판단 — 출시 전이라고 축소할 부분이 아니다. 부수 효과로 "성공 후 발행자 롤백 시 환불 기록이 사라지던" 구멍도 막힌다(재시도가 no-op → 이중환불 방지) |
| 2026-08-10 | **주문 잔액을 행에서 읽히게** — `payment_order.refunded_amount` 비정규화(V14) + 전액환불 시 `status=CANCELED`(부분은 `DONE` 유지) | 승인 사실인 `status` 는 환불해도 `DONE` 이라 "이 주문 환불됐나/얼마 남았나"를 매번 `refund_order` 집계로만 알 수 있었다 — **CS·회계에서 테이블을 눈으로 못 읽는 게 실질 비용**. 회차당 주문이 N개가 되는 차액 결제(#204) 뒤엔 더 안 보인다. `refund_order`(원장)와 같은 트랜잭션에서 갱신되는 캐시이며, 환불 실행부를 `applyCancel` 한 곳으로 모아 clamp·멱등을 보장 |
| 2026-08-09 | **전 회차 통일** — `PAYMENT_PENDING` 제거, 결제 전이는 항상 `PENDING→ACCEPT_PENDING`. 강사 거절은 전 회차, 결제 후 학생 취소=전액환불, 결제 후 슬롯 변경은 금액 증가 금지·감소는 차액 환불 | 1회차만 바꾼 상태가 상태기계를 반쪽으로 만들어 2회차 강사 수락이 400 으로 막히는 seam 이 있었다. 결제가 앞으로 당겨졌으므로 강사 "일정조정 제안"도 결제 후 시점이 되고, 그러면 학생은 결제가 아니라 ㅇㅋ/ㄴㄴ만 하면 돼 사전승인용 상태값이 아예 불필요해진다. 잔존 행은 Flyway `V13` 이 `PENDING` 으로 이관(enum 파싱 폭발 방지) |
| 2026-08-10 | **이니시스 콜백 CORS 제외** — `/payments/inicis/return` 만 전역 origin allowlist 밖도 허용(FE 핑퐁) | 앱 WebView 결제완료 후 콜백 단계에서 `Invalid CORS request`(403). 콜백은 결제창(`paypro.inicis.com`)·WebView 가 하는 **cross-origin form POST(navigation)** 라 CORS(브라우저 JS/fetch 보호장치)의 대상이 아니고, 진위는 `P_AUTH_TID`+서버승인이 보장 → **CORS 는 이 경로의 보안 경계가 아니다**. FE 가 WebView `document.origin` 을 웹 도메인으로 위장(origin 스푸핑)하던 야매를 근본에서 제거 — `corsConfigurationSource()` 가 콜백만 `allowedOriginPattern("*")`(+`allowCredentials=false`)로 `/**` 보다 먼저 등록해 CORS 검사에서 뺀다. 전역 allowlist 는 유지(다른 경로는 여전히 restrictive). 구현: [docs/architecture/payment.md](../architecture/payment.md) §5 |

### 환불 — 수강 종료(남은 회차 환불) (2026-06-28 구현)

진행 중 학생의 "환불신청" = 수강 종료. `POST /enrollments/{enrollmentId}/refund` → 활성·미완료 회차를 전부 취소하고 회차별로 환불.

- **회차별 산정**(`RefundCalculator`): 수강 완료(done)=0 / 미배정 회차=수강료÷N(100%, 부대·패널티 0) / 배정취소=(수강료÷N + 부대)×환불율. EXTRA=부대만. 부대는 결제 완료분만.
- **환불율**: 당일 0 / 전날 50 / 2일전 70 / 3일전+ 100, **신청 1h 내 100**. (코드 상수 — SiteSettings 이전은 튜닝 필요 시.)
- **실행**: **수강료는 1회차 결제주문에 전액** 있으므로 수강료 몫 합 = 1회차 주문 **부분취소**, 부대 몫 = 각 회차 주문 부분취소. PG 부분취소(토스 `cancelAmount` / 이니시스 `partialRefund` + `price`·`confirmPrice`[취소 후 잔액]). `RefundOrder` 주문별 기록. stub/실연동은 결제와 동일.
- 응답 `RefundQuote{total, lines[]}` — 미리보기와 실행이 같은 값.

## 미해결 / 확장

- 🟢 **webhook** — **MVP 보류**(2026-06-28 결정, 위 히스토리). 가상계좌 안 받으면 confirm·환불 다 동기라 불필요. 거래량↑/가상계좌 재검토 시 별도 PR(비동기 입금·out-of-band 취소·reconciliation + 서명 검증). **가상계좌 받기로 하면 그때 필수.**
- 🟢 **결제 대기/응답 만료 + 자동환불** — 선결제 전환으로: 미결제 `PENDING` **12h**(createdAt) 만료·슬롯 해제(환불 없음), 결제완료 `ACCEPT_PENDING` **24h**(결제시각) 강사 무응답 만료 + **전액 자동환불**, 강사 거절 시 자동환불. `EnrollmentExpiryService` + `SiteSettings` TTL + enrollment 이벤트. 만료/거절 푸시 알림은 후속.
- 🟢 **가격은 모두 스냅샷 (live 재계산 안 함 — 결정됨)** — 수강료(enrollment 스냅샷)·입장료·장비 전부 신청/일정 시점 가격으로 박제. 결제 시 현재가로 다시 계산하지 않는다. **학생이 본 가격 보장 + 환불 정산 깔끔**(2026-06-28 결정 — 옛 "입장료/장비 live 재계산" 안건 폐기). 권위 금액은 클라가 아닌 서버 스냅샷 합이라 보안도 무관.
- 🟢 **정산 수수료 분해** — PG 3.4% + 플랫폼 6.6%(enrollment `아직 안 한 것`과 함께).
- 🟢 **캘린더 미결제 별도 표시** — 결제완료·점유 상태(ACCEPT_PENDING/CONFIRMED)는 confirmed 점유 버킷에 합산. 미결제 PENDING 을 별도 표시하려면 카운트 분리 후속.

## 본인이 직접 처리할 것 (코드 밖)

### 토스 (심사 대기 중 — 계속 태워둠)
- staging 배포 + `PAYMENT_MODE=toss`/`TOSS_CLIENT_KEY`/`TOSS_SECRET_KEY` 주입(문서용 테스트 키 → 발급 후 실키). ECS task def / Parameter Store.
- 토스 PG **심사 신청** 완료 — 2026-07 기준 **심사 시작까지 3개월** 통보. 풀리면 `PAYMENT_MODE=toss` 로 flip 만 하면 된다(코드 변경 0).

### 이니시스 (런칭 경로)
- **온보딩**(사전심사 통과 → 전자계약 → MID 발급 → 카드사심사 7~10영업일). 테스트/운영은 엔드포인트가 아니라 **MID**(테스트 `INIpayTest`)로 갈리므로, 개발은 테스트 MID 로 지금 가능하고 실 라이브는 MID·키 발급 후 env 스왑.
- **지급대행 가입(중개 필수)** — 학생→플랫폼 결제 후 강사 정산을 이니시스가 대행. 계약 완료 후 상점관리자페이지에서 추가 신청. **런칭은 상점관리자페이지 수동 운영(코드 0)**, 지급대행 API 는 후속. ⚠️ 강사 **정산계좌는 국내·본인명의만**(타명의/해외계좌 입점불가) — 강사 온보딩에서 이 제약을 받을 것. 수수료 건당 500원.
- **보증보험 가입** — 장기/비실물 선불이라 필수(케이스마트인슈 (02)719-8488, 1000만 방향). 최고 객단가 = 1회차 결제액(수강료 전액+부대)로 승인한도 산정.
- **사이트 수정**(카드사심사 요건) — 상품명 "체험"→**"프리다이버 자격 과정 1일 레슨"**(⚠️ 모니터링에서 일반인 레슨으로 확인되면 계약불이익 — 실제로 자격 과정 입문 회차라는 서사 유지), 하단 **사업자정보·통신판매신고번호·민원책임 문구**("모든 거래 책임·환불·민원은 풍덩이 처리"). ⚠️ 이 민원책임 문구가 **약관 refund §6("강사가 당사자")과 상충** → 법무 검토.
- **✅ 실 왕복 검증 완료**(2026-08-07, staging 테스트 MID) — 실카드로 결제창→승인→환불(iniapi)→CANCELLED 전 사이클 성공(카드 승인·취소 문자 수신). 환불 tid 필드 OK(저장 tid로 환불 승인), hashData·전액취소 정상. prod MID(`plopol1192`)는 카드사심사 flip 때 재확인.
- **✅ 환불 clientIp 등록 불요**(2026-08-07 검증) — 기본 clientIp로 환불 통과 → **고정 egress(fck-nat) 불필요**, 환불 자동화 인프라 부담 0. (prod MID 재확인 권장이나 강신호. prod에서 IP 제약 나타나면 fck-nat ~$7/월 옵션.)
- 시크릿 주입: `INICIS_MID`/`INICIS_HASH_KEY`/`INICIS_API_KEY`/`INICIS_CLIENT_IP`/`INICIS_RET_URL`/`INICIS_RETURN_WEB_SUCCESS|FAIL`. hashKey·apiKey 는 **Parameter Store SecureString**.

### FE
- `prepare` 에 **`client`(web/app)** 전송 — 이니시스 콜백 리다이렉트 타겟 선택용. `mobile` 과 독립 축(`mobile` → `P_DEVICE_TYPE`).
- `prepare` 응답의 **`provider` 로 분기**:
  - `TOSS`(위젯) / `STUB`(결제창 없이 바로 confirm) → **FE 가 `/payments/confirm`** 호출(`pgPayload`=토스 `paymentKey`).
  - `INICIS` → **FE 는 confirm 안 함.** `INIPayPro_v2.js`(`https://paypro.inicis.com/std/payment/js/INIPayPro_v2.js`)를 로드하고 `INIPayPro.requestPayment(params)` 로 결제창 구동(`params` = prepare 응답의 P_ 파라미터, ⚠️ **구버전 `stdpay.inicis.com` 아님**). 결제창이 BE 콜백(`P_NEXT_URL`)으로 form POST → BE 승인 후 **성공/실패 URL 로 GET 리다이렉트**(web `{origin}/payment/success` · app `plop://payment/success`, 쿼리 `orderId&orderNo&status`).
- 이니시스 성공화면: 리다이렉트 도착 → **`GET /payments/orders/{orderId}` 조회**로 금액·상태 렌더(새로고침·딥링크 재진입도 이걸로 복구).
- 계약 상세: [docs/api-clients/types.ts](../api-clients/types.ts) 의 payment 섹션. **BE 직접 처리분**: `INICIS_RET_URL`(BE 콜백 URL) + `INICIS_RETURN_WEB_SUCCESS/FAIL`(환경별 web) 주입; app 스킴은 기본값(`plop://payment/...`).

## 관련 메모리

- [[payment_pg_kcp_switch]] — PG 여정: 토스(적체)→KCP(중개 미지원 폐기)→이니시스(지급대행·건당제·INIpay PRO)
- [[phase_4_deployment_decisions]] — Toss PG 심사가 staging 배포를 요구(Phase 4 진입 트리거)
- [[enrollment_domain_concept]] — (선결제 2026-08-07) 1회차는 신청 즉시 결제 → ACCEPT_PENDING → 강사 수락/거절(자동환불)
- [[address_geocode_domain]] — 동일한 외부 경계 stub/real 교체 패턴
