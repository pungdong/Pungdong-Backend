package com.diving.pungdong.payment;

import com.diving.pungdong.enrollment.EnrollmentRound;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
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

    /**
     * 낙관적 락 — 승인↔만료 스윕, 동시 환불이 같은 주문을 blind overwrite 하는 것을 막는다. 예: 만료 스윕이
     * {@code DONE} 주문을 {@code FAILED} 로 덮어써 모든 환불 경로에서 안 보이게 하던 문제(진 쪽이 롤백된다).
     */
    @Version
    @Column(nullable = false)
    private long version;

    /** 토스 주문번호 — prepare 가 생성한 서버 식별자. confirm 의 멱등 키이자 amount 조회 키. */
    @Column(nullable = false)
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_round_id")
    private EnrollmentRound enrollmentRound;

    /** 서버가 정한 권위 금액(원). 클라이언트 입력 신뢰 금지 — confirm 시 일치 검증. */
    private int amount;

    /**
     * 이 주문에서 <b>지금까지 환불된 총액</b>(원). {@code amount - refundedAmount} = <b>취소가능 잔액</b>.
     *
     * <p><b>왜 비정규화하나</b>: 승인 사실인 {@link #status} 는 환불해도 {@code DONE} 이라, 이 컬럼이 없으면
     * "이 주문 환불됐나 / 얼마 남았나"를 매번 {@code refund_order} 집계로만 알 수 있다 — CS·회계에서 테이블을
     * 눈으로 읽지 못한다. 회차당 주문이 여러 개가 되면(차액 결제) 더 안 보인다. 그래서 잔액을 행에 들고 있는다.
     *
     * <p>{@code refund_order}(이력)가 <b>원장</b>이고 이 값은 그 합의 <b>캐시</b> 다 — 둘은 같은 트랜잭션에서
     * 함께 갱신되며, 어긋나면 {@code refund_order} 가 진실이다.
     *
     * <p>읽는 법: {@code DONE + refunded=0} 정상 · {@code DONE + refunded>0} 부분환불 · {@code CANCELED} 전액환불.
     */
    @Column(nullable = false)
    @Builder.Default
    private int refundedAmount = 0;

    /** 취소가능 잔액 = 승인액 − 기환불액. 환불은 이 값을 넘을 수 없다. */
    public int refundableAmount() {
        return amount - refundedAmount;
    }

    /** 토스 위젯/영수증 표시용 주문명(예: "프리다이빙 입문 (1회차)"). */
    private String orderName;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    /**
     * 이 주문이 <b>어느 PG 에 묶였는가</b> — prepare 가 결제창을 띄운 시점의 PG 로 박제된다.
     *
     * <p>⚠️ <b>왜 주문에 저장하는가</b>: 전역 설정({@code pungdong.payment.mode})은 <b>신규 주문</b>이 어디로 갈지만
     * 정한다. 주문은 설정보다 오래 산다 — 이니시스로 결제한 뒤 토스로 전환하면, 그 주문의 <b>승인·환불은 여전히 이니시스</b> 로
     * 가야 한다. 전역 설정으로 라우팅하면 존재하지 않는 거래에 취소를 보내 <b>돈은 받고 환불은 실패</b>한다.
     * 라우팅은 {@link PaymentGatewayRegistry#forOrder} 가 이 값으로 한다.
     *
     * <p>legacy 행(이 컬럼 도입 전)은 null 이며, 그 경우에만 현재 활성 게이트웨이로 폴백한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PaymentProvider provider;

    /**
     * 결제를 시작한 클라이언트(web/app) — <b>이니시스 콜백 리다이렉트 타겟</b> 선택용. prepare 가 박제한다.
     * 이니시스는 결제창→BE(P_NEXT_URL)→GET 리다이렉트 구조라, 콜백이 이 값으로 web URL/app 스킴을 고른다({@link PaymentClient}).
     * TOSS/STUB 는 FE 가 리턴을 처리하므로 안 쓴다. null(legacy)이면 web 으로 폴백.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private PaymentClient client;

    /** PG 거래 식별자 — 토스 {@code paymentKey} / 이니시스 {@code P_TID}. 승인 후 채워지며 취소에 쓴다. */
    private String paymentKey;

    /** 결제수단(카드/간편결제/가상계좌 등). 승인 후 채워짐. */
    private String method;

    /** PG 승인 시각(승인 전 null). */
    private OffsetDateTime approvedAt;

    /**
     * <b>차액 결제 주문이 적용할 목표 슬롯</b> — null 이면 일반(신청) 결제 주문이다.
     *
     * <p><b>왜 주문이 들고 있나</b>: 더 비싼 슬롯으로 옮길 때 "결제 대기"가 필요한데, 그걸 예약 상태
     * ({@code EnrollmentStatus})에 두면 방금 없앤 {@code PAYMENT_PENDING} 류가 되살아난다. 대신 <b>대기를 주문에</b>
     * 두면 회차는 내내 {@code ACCEPT_PENDING}/{@code CONFIRMED} 를 유지하고, 승인되는 순간 슬롯이 교체된다.
     * 학생이 결제를 포기하면 <b>주문만 만료</b>되고 예약은 원래 슬롯 그대로다 — 롤백할 것이 없다.
     */
    private LocalDate targetDate;

    /** 목표 슬롯 이용권 ref. {@link #targetDate} 와 한 벌. */
    private String targetTicketRef;

    /** 목표 슬롯 시작 시각. */
    private LocalTime targetBlockStart;

    /** 목표 슬롯 종료 시각. */
    private LocalTime targetBlockEnd;

    /** 이 주문이 슬롯 변경 차액 결제인가 — 승인 시 슬롯 교체를 수반한다. */
    public boolean isSlotChange() {
        return targetDate != null && targetTicketRef != null
                && targetBlockStart != null && targetBlockEnd != null;
    }

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
