package com.diving.pungdong.payment;

import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 환불 <b>시도</b> 1건 — 한 {@link PaymentOrder}(결제 주문)에 대한 (부분) 취소 기록이자 <b>원장</b>.
 * 수강 종료(남은 회차 환불)·강사 거절/학생 취소(회차 전액)·일정 변경(차액)이 각각 1건씩 남긴다.
 *
 * <p><b>성공만이 아니라 시도를 남긴다</b>(2026-08-10) — PG 호출 <b>직전</b>에 {@code REQUESTED} 로 별도
 * 트랜잭션에 커밋해두고, 결과에 따라 {@code DONE}/{@code FAILED} 로 갱신한다. 발행자 트랜잭션이 롤백돼도
 * 이 기록은 남으므로 <b>재시도 판단과 PG 원장 대사(reconciliation)</b> 가 가능하고, 무엇보다
 * <b>"PG 엔 취소가 됐는데 우리 DB 엔 없는" 부분실패를 탐지</b> 할 수 있다({@code REQUESTED} 잔존 = 결과 미확인).
 *
 * <p>{@code PaymentOrder.refundedAmount} 는 이 원장의 {@code DONE} 합을 캐시한 값 — 어긋나면 <b>이 표가 진실</b> 이다.
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

    /** 이 주문에서 취소(환불)한 금액(원) — 부분취소 가능. {@code REQUESTED} 단계에선 <b>요청액</b>. */
    private int amount;

    private String reason;

    /**
     * 시도 상태 — {@code REQUESTED}(PG 호출 직전 선기록) → {@code DONE}(승인) / {@code FAILED}(PG 거절).
     *
     * <p><b>{@code REQUESTED} 로 남아 있는 행은 "결과를 모르는 시도"</b> 다(프로세스 급사·타임아웃 등).
     * PG 에는 취소가 반영됐는데 우리가 못 받았을 수 있으므로 <b>대사 대상</b> 이며, 그 주문의 추가 자동환불은
     * 막힌다(이중환불 방지). 잔액 계산엔 {@code DONE} 만 센다.
     */
    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    /** 시도 시각(선기록). */
    private OffsetDateTime createdAt;

    /** 결과 확정 시각 — {@code DONE}/{@code FAILED} 로 갱신된 순간. {@code REQUESTED} 면 null. */
    private OffsetDateTime completedAt;

    /** PG 거절 코드(이니시스 {@code resultCode} / 토스 {@code code}) — 실패 진단·대사용. */
    @Column(length = 32)
    private String failureCode;

    /** PG 거절 사유 원문 — 길 수 있어 잘라 저장. */
    @Column(length = 255)
    private String failureMessage;
}
