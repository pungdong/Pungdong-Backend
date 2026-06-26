package com.diving.pungdong.payment.dto;

import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 결제 승인 응답 — 결제 상태 + 그 결과로 확정된 수강신청 상태. FE 가 완료 화면에 쓴다. */
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
                .enrollmentId(order.getEnrollment() == null ? null : order.getEnrollment().getId())
                .enrollmentStatus(order.getEnrollment() == null ? null : order.getEnrollment().getStatus())
                .build();
    }
}
