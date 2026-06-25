package com.diving.pungdong.payment.dto;

import com.diving.pungdong.payment.PaymentOrder;
import lombok.Builder;
import lombok.Getter;

/**
 * 결제 준비 응답 — FE 가 토스 결제위젯을 띄우는 데 필요한 값. 금액·주문번호는 <b>서버가 정한 값</b>(권위)이라
 * FE 는 그대로 위젯에 넘긴다(임의 변경 시 승인 거절). {@code clientKey} 는 공개값(위젯 로드용).
 */
@Getter
@Builder
public class PaymentPrepareResponse {

    private String orderId;
    private int amount;
    private String orderName;
    private String clientKey;
    private String customerKey;

    public static PaymentPrepareResponse of(PaymentOrder order, String clientKey, String customerKey) {
        return PaymentPrepareResponse.builder()
                .orderId(order.getOrderId())
                .amount(order.getAmount())
                .orderName(order.getOrderName())
                .clientKey(clientKey)
                .customerKey(customerKey)
                .build();
    }
}
