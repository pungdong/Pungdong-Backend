package com.diving.pungdong.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

/** 운영자 수동 환불 요청 — {@code amount} 생략(null)이면 취소가능 잔액 전액. 사유는 원장에 그대로 남는다. */
@Getter @Setter
@NoArgsConstructor
public class ManualRefundRequest {

    /** 취소액(원). null = 잔액 전액. 잔액 초과는 400(clamp 하지 않음 — 운영자가 숫자를 확인하게). */
    @Positive(message = "취소액은 1원 이상이어야 합니다")
    private Integer amount;

    /** 환불 사유 — RefundOrder.reason 에 "운영자 수동 환불: " 접두로 기록. */
    @NotBlank(message = "환불 사유를 입력해 주세요")
    @Size(max = 200, message = "환불 사유는 200자 이내")
    private String reason;
}
