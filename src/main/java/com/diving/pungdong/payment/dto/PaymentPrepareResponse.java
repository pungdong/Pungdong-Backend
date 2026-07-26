package com.diving.pungdong.payment.dto;

import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentProvider;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 결제 준비 응답 — FE 가 결제창을 띄우는 데 필요한 값. 금액·주문번호는 <b>서버가 정한 값</b>(권위)이라
 * FE 는 그대로 결제창에 넘긴다(임의 변경 시 승인 거절).
 *
 * <p>{@code provider} 로 <b>FE 가 결제창 구동 방식을 분기</b>하고, {@code params} 에서 그 PG 에 필요한 값을 꺼낸다:
 * <ul>
 *   <li>{@code TOSS} — {@code clientKey}(공개), {@code customerKey} → 결제위젯 v2</li>
 *   <li>{@code KCP} — {@code siteCd} + (모바일) {@code approvalKey}·{@code payUrl}·{@code traceNo} → 표준결제창</li>
 *   <li>{@code STUB} — 결제창 없음. 바로 confirm 호출 가능(로컬).</li>
 * </ul>
 */
@Getter
@Builder
public class PaymentPrepareResponse {

    private String orderId;     // PG 멱등키 — 결제창에 그대로 넘김(내부 식별)
    private String orderNo;     // CS·고객용 주문번호(PD-YYMMDD-XXXXXXXX, 날짜+난독화)
    private int amount;
    private String orderName;

    /** 어떤 PG 로 띄울지 — FE 분기 근거. */
    private PaymentProvider provider;

    /** PG별 결제창 구동값. 키 목록은 provider 에 따라 다르다(위 표 참고). */
    private Map<String, String> params;

    public static PaymentPrepareResponse of(PaymentOrder order, String orderNo,
                                            PaymentProvider provider, Map<String, String> params) {
        return PaymentPrepareResponse.builder()
                .orderId(order.getOrderId())
                .orderNo(orderNo)
                .amount(order.getAmount())
                .orderName(order.getOrderName())
                .provider(provider)
                .params(params)
                .build();
    }
}
