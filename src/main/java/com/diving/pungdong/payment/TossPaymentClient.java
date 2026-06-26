package com.diving.pungdong.payment;

import java.time.OffsetDateTime;

/**
 * 토스페이먼츠 결제 승인 경계 — BE 와 외부 PG(토스) 사이. {@code address}/{@code consent} 와 동일한
 * "interface + 구현 교체(@ConditionalOnProperty)" 패턴.
 *
 * <ul>
 *   <li>{@link RealTossPaymentClient} — 실 토스 호출({@code /v1/payments/confirm}). staging/prod.</li>
 *   <li>{@link StubTossPaymentClient} — 로컬 stub(토스 미호출, 즉시 DONE). 기본값.</li>
 * </ul>
 *
 * <p>결제위젯 v2 흐름에서 <b>승인은 서버가</b> 한다 — FE 위젯이 받은 {@code paymentKey} 를 BE 가 시크릿 키로
 * 승인 호출. 시크릿 키는 BE 밖으로 안 나간다(juso 승인키 기조와 동일). FE 엔 클라이언트 키(공개)만 내려간다.
 */
public interface TossPaymentClient {

    /**
     * 결제 승인 — 토스 {@code POST /v1/payments/confirm} 호출(멱등 키 = orderId). 성공 시 DONE 결과.
     * 토스가 거절(금액 불일치·이미 처리·잘못된 키 등)하면 {@link com.diving.pungdong.global.advice.exception.BadRequestException}.
     *
     * @param amount 서버가 정한 권위 금액(원). 토스도 이 값으로 승인 — 위젯에서 결제한 금액과 다르면 토스가 거절.
     */
    TossConfirmResult confirm(String paymentKey, String orderId, int amount);

    /** 승인 결과 — 후속 표시/정산에 필요한 최소 필드만. */
    record TossConfirmResult(String status, String method, OffsetDateTime approvedAt, String receiptUrl) {
    }
}
