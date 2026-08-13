package com.diving.pungdong.payment;

/**
 * 이니시스 콜백 수신 판정 — 무슨 콜백이 왔고 어떻게 처리됐나(대사·공격탐지용).
 *
 * <ul>
 *   <li>{@code UNKNOWN_ORDER} — 우리가 모르는 {@code P_OID}(위조/오배송 후보).</li>
 *   <li>{@code AUTH_FAILED} — 결제창 인증 실패({@code P_STATUS != "00"}) — 승인 호출 없이 끝.</li>
 *   <li>{@code APPROVED} — 서버승인까지 성공.</li>
 *   <li>{@code APPROVAL_FAILED} — 승인 호출이 예외로 실패(거절·전송오류 등). ⚠️ 카드가 청구됐을 수 있어 대사 대상.</li>
 * </ul>
 */
public enum CallbackOutcome {
    UNKNOWN_ORDER,
    AUTH_FAILED,
    APPROVED,
    APPROVAL_FAILED
}
