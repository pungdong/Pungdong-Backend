package com.diving.pungdong.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;

/** 결제 준비 요청 — 수락된(PAYMENT_PENDING) 수강신청에 대해 주문 생성. */
@Getter @Setter
@NoArgsConstructor
public class PaymentPrepareRequest {

    @NotNull
    private Long enrollmentId;
}
