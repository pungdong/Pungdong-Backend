package com.diving.pungdong.payment;

import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 결제 <b>승인 시도</b> 1건 — 한 {@link PaymentOrder} 에 대한 PG 승인(청구) 기록이자 <b>원장</b>.
 * 환불의 {@link RefundOrder} 와 대칭이다.
 *
 * <p><b>왜 필요한가</b>(C1): 승인은 {@code applyConfirm} 안에서 PG 를 호출(청구)한 <b>뒤에</b> 주문/회차를 확정한다.
 * 그 확정이 어디선가 실패해 트랜잭션이 롤백되면 <b>카드는 청구됐는데 우리 DB 엔 흔적이 0</b> 이 된다(주문 READY,
 * paymentKey null) — 대사로도 못 잡고 환불도 못 한다. 환불엔 {@link RefundLedger} 가 있는데 승인엔 대응물이 없었다.
 *
 * <p>그래서 PG 호출 <b>직전</b> {@code ATTEMPTED} 로 선기록하고, 승인되면 {@code APPROVED}(+pgTransactionId)로
 * <b>별도 트랜잭션에서 즉시 커밋</b>한다({@link PaymentApprovalLedger}). 이후 확정이 롤백돼도 청구 사실은 이 표에 남아,
 * 재시도가 재청구 없이 그 결과로 전진 확정한다(정확히 한 번 청구 / 여러 번 적용).
 */
@Entity
@Table(name = "payment_approval")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class PaymentApproval {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_order_id")
    private PaymentOrder paymentOrder;

    /** 승인 요청 금액(원) — 주문 권위값. */
    private int amount;

    /** 승인을 보낸 PG — 주문에 박제된 provider(대사·라우팅 참고용). */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PaymentProvider provider;

    /**
     * 시도 상태 — {@code ATTEMPTED}(PG 호출 직전 선기록) → {@code APPROVED}(승인, tid 확보) / {@code FAILED}(거절).
     * {@code ATTEMPTED} 잔존 = 결과 미확인(대사 대상, 재승인 차단). 자세히는 {@link ApprovalStatus}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ApprovalStatus status;

    /** PG 거래 식별자(토스 paymentKey / 이니시스 P_TID) — {@code APPROVED} 시 확보. 취소·확정에 쓴다. */
    private String pgTransactionId;

    /** 결제수단 라벨(카드 등) — {@code APPROVED} 시 확보. */
    private String method;

    /** PG 가 알려준 승인 시각(PG 시계) — {@code APPROVED} 시 확보. 주문 확정에 그대로 싣는다. */
    private OffsetDateTime approvedAt;

    /** 시도 시각(선기록). */
    private OffsetDateTime attemptedAt;

    /** 결과 확정 시각 — {@code APPROVED}/{@code FAILED} 로 갱신된 순간. {@code ATTEMPTED} 면 null. */
    private OffsetDateTime resolvedAt;

    /** PG 거절 코드 — 실패 진단·대사용. */
    @Column(length = 32)
    private String failureCode;

    /** PG 거절 사유 원문 — 길 수 있어 잘라 저장. */
    @Column(length = 255)
    private String failureMessage;
}
