package com.diving.pungdong.payment;

import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 환불 1건 — 한 {@link PaymentOrder}(결제 주문)에 대한 (부분) 취소 기록. 수강 종료(남은 회차 환불) 시 주문별로
 * 1건씩 생긴다(수강료=1회차 주문 부분취소, 부대=각 회차 주문). 토스 취소 호출의 BE 측 감사 기록.
 */
@Entity
@Table(name = "refund_order")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RefundOrder {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_order_id")
    private PaymentOrder paymentOrder;

    /** 이 주문에서 취소(환불)한 금액(원) — 부분취소 가능. */
    private int amount;

    private String reason;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    private LocalDateTime createdAt;
}
