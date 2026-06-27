package com.diving.pungdong.payment.dto;

import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 결제 승인 응답 — 결제 상태 + 그 결과로 확정된 회차 상태. {@code enrollmentId} 는 회차 id. FE 가 완료 화면에 쓴다. */
@Getter
@Builder
public class PaymentConfirmResponse {

    private String orderId;
    private PaymentStatus status;
    private int amount;
    private OffsetDateTime approvedAt;
    private Long enrollmentId;
    private EnrollmentStatus enrollmentStatus;

    public static PaymentConfirmResponse of(PaymentOrder order) {
        return PaymentConfirmResponse.builder()
                .orderId(order.getOrderId())
                .status(order.getStatus())
                .amount(order.getAmount())
                .approvedAt(order.getApprovedAt())
                .enrollmentId(order.getEnrollmentRound() == null ? null : order.getEnrollmentRound().getId())
                .enrollmentStatus(order.getEnrollmentRound() == null ? null : order.getEnrollmentRound().getStatus())
                .build();
    }
}
