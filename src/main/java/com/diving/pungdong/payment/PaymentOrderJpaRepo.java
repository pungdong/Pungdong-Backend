package com.diving.pungdong.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentOrderJpaRepo extends JpaRepository<PaymentOrder, Long> {

    /** confirm 진입점 — orderId 로 권위 금액·소유 회차 조회. */
    Optional<PaymentOrder> findByOrderId(String orderId);

    /** prepare 멱등 — 같은 회차의 같은 상태 주문 재사용(중복 READY 생성 방지). */
    /**
     * ⚠️ <b>단건 전제</b> — {@code READY} 조회 전용이다(한 회차에 결제창 대기 주문은 하나, prepare 가 멱등 재사용).
     * {@code DONE} 은 회차당 <b>여러 건</b>일 수 있으므로(차액 추가 결제) 반드시
     * {@link #findByEnrollmentRoundIdAndStatusOrderByIdAsc} 를 쓴다 — 여기로 조회하면 2건째부터 예외가 난다.
     */
    Optional<PaymentOrder> findByEnrollmentRoundIdAndStatus(Long roundId, PaymentStatus status);

    /** 그 회차의 해당 상태 주문들 — 결제 순서(id 오름차순). 원결제가 먼저, 차액이 뒤. */
    List<PaymentOrder> findByEnrollmentRoundIdAndStatusOrderByIdAsc(Long roundId, PaymentStatus status);

    /** 차액 결제 만료 스위프 — 목표 슬롯을 단 채 결제창 window 를 넘긴 READY 주문들. */
    List<PaymentOrder> findByStatusAndTargetDateIsNotNullAndCreatedAtBefore(PaymentStatus status, OffsetDateTime cutoff);
}
