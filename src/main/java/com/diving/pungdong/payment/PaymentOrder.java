package com.diving.pungdong.payment;

import com.diving.pungdong.enrollment.EnrollmentRound;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 결제 주문 1건 — 한 {@link EnrollmentRound}(수락된 회차)의 결제. 토스 결제위젯 v2 흐름의 BE 측 기록.
 * 다회차: 결제 단위는 회차 — 1회차는 수강료+부대, 2회차~ 부대만(수강료는 1회차에 전액).
 *
 * <p><b>왜 새 엔티티인가</b>: 레거시 {@code domain/payment/Payment} 는 옛 예약 플로우 전용(가격 산술만, PG
 * 트랜잭션 필드 없음)이라 건드리지 않는다. enrollment 도메인 옆에 결제를 1급으로 둔다(package-by-feature).
 *
 * <p><b>권위 금액</b>: {@link #amount} 는 prepare 시점에 서버가 정한 금액(원). 클라이언트가 보낸 금액은
 * 신뢰하지 않는다 — confirm 의 amount 가 이 값과 다르면 거절, 토스 승인도 이 값으로 호출.
 *
 * <p><b>멱등 식별자</b>: {@link #orderId} 는 토스에 넘기는 주문번호(6~64자 {@code [A-Za-z0-9-_]}). unique.
 */
@Entity
@Table(name = "payment_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_order_order_id", columnNames = "orderId"))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class PaymentOrder {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 토스 주문번호 — prepare 가 생성한 서버 식별자. confirm 의 멱등 키이자 amount 조회 키. */
    @Column(nullable = false)
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_round_id")
    private EnrollmentRound enrollmentRound;

    /** 서버가 정한 권위 금액(원). 클라이언트 입력 신뢰 금지 — confirm 시 일치 검증. */
    private int amount;

    /** 토스 위젯/영수증 표시용 주문명(예: "프리다이빙 입문 (1회차)"). */
    private String orderName;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    /** 토스 승인 후 발급되는 결제 키(승인 전 null). */
    private String paymentKey;

    /** 결제수단(카드/간편결제/가상계좌 등). 승인 후 채워짐. */
    private String method;

    /** 토스 승인 시각(승인 전 null). */
    private OffsetDateTime approvedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
