package com.diving.pungdong.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentOrderJpaRepo extends JpaRepository<PaymentOrder, Long> {

    /** confirm 진입점 — orderId 로 권위 금액·소유 enrollment 조회. */
    Optional<PaymentOrder> findByOrderId(String orderId);

    /** prepare 멱등 — 같은 enrollment 의 같은 상태 주문 재사용(중복 READY 생성 방지). */
    Optional<PaymentOrder> findByEnrollmentIdAndStatus(Long enrollmentId, PaymentStatus status);
}
