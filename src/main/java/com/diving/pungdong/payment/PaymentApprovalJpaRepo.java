package com.diving.pungdong.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

/** 결제 승인 원장 — 주문별 시도 조회(전진 확정·대사). {@link PaymentApproval}. */
public interface PaymentApprovalJpaRepo extends JpaRepository<PaymentApproval, Long> {

    List<PaymentApproval> findByPaymentOrderIdAndStatus(Long paymentOrderId, ApprovalStatus status);

    /** 대사 스윕 — 일정 시간 넘게 결과 미확인({@code ATTEMPTED})으로 남은 승인 시도. */
    List<PaymentApproval> findByStatusAndAttemptedAtBefore(ApprovalStatus status, OffsetDateTime cutoff);
}
