package com.diving.pungdong.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface RefundOrderJpaRepo extends JpaRepository<RefundOrder, Long> {

    /** 한 결제주문에 이미 성사된 환불들 — 취소가능잔액(승인액 − 기취소액) 계산용. */
    List<RefundOrder> findByPaymentOrderIdAndStatus(Long paymentOrderId, RefundStatus status);

    /** 대사 스윕 — 일정 시간 넘게 결과 미확인({@code REQUESTED})으로 남은 환불 시도. */
    List<RefundOrder> findByStatusAndCreatedAtBefore(RefundStatus status, OffsetDateTime cutoff);
}
