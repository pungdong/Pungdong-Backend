package com.diving.pungdong.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

/**
 * 결제 승인 요청 — 위젯 결제 성공 리다이렉트({@code successUrl?paymentKey&orderId&amount})에서 FE 가 받은
 * 3개 값을 그대로 전달. 서버는 {@code amount} 를 신뢰하지 않고 주문의 권위 금액과 대조한다(불일치 시 거절).
 */
@Getter @Setter
@NoArgsConstructor
public class PaymentConfirmRequest {

    @NotBlank
    private String paymentKey;

    @NotBlank
    private String orderId;

    @NotNull
    @Positive
    private Integer amount;
}
