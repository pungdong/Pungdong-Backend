package com.diving.pungdong.payment;

/**
 * 결제 주문(PaymentOrder) 상태 — 토스 결제 라이프사이클의 BE 측 투영.
 *
 * <ul>
 *   <li>{@link #READY} — 주문 생성(prepare). 권위 금액 박제, 위젯 결제 대기. 아직 승인 전.</li>
 *   <li>{@link #DONE} — 토스 승인 완료(confirm). enrollment 를 CONFIRMED 로 확정시킨 상태.</li>
 *   <li>{@link #CANCELED} — 결제 취소/환불(후속 — 환불 상태기계는 미구현).</li>
 *   <li>{@link #FAILED} — 승인 실패(후속).</li>
 * </ul>
 */
public enum PaymentStatus {
    READY,
    DONE,
    CANCELED,
    FAILED
}
