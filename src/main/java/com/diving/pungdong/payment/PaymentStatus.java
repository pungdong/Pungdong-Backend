package com.diving.pungdong.payment;

/**
 * 결제 <b>주문</b>({@link PaymentOrder}) 상태 — <b>돈의 축</b>. 예약의 축인
 * {@code EnrollmentStatus}(PENDING/ACCEPT_PENDING/CONFIRMED…)와 <b>독립</b>이다.
 *
 * <ul>
 *   <li>{@link #READY} — 주문 생성(prepare). 권위 금액 박제, 결제창 대기. 아직 승인 전.</li>
 *   <li>{@link #DONE} — <b>PG 승인 완료</b>(confirm). "결제됐다"일 뿐 "예약 확정"이 아니다 — 선결제라
 *       승인 시점의 회차는 {@code ACCEPT_PENDING}(강사 결정 대기)이고, 확정은 강사 수락 뒤다.</li>
 *   <li>{@link #CANCELED} — <b>전액 환불됨</b>(취소가능 잔액 0). 부분환불은 {@code DONE} 을 유지하고
 *       {@code PaymentOrder.refundedAmount} 로 표현한다.</li>
 *   <li>{@link #FAILED} — 승인 실패(후속 — 현재 미사용, 실패는 예외 전파로 롤백된다).</li>
 * </ul>
 *
 * <p>읽는 법: {@code DONE + refunded=0} 정상 · {@code DONE + refunded>0} 부분환불 · {@code CANCELED} 전액환불.
 */
public enum PaymentStatus {
    READY,
    DONE,
    CANCELED,
    FAILED
}
