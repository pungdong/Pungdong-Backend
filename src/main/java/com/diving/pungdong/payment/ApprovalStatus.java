package com.diving.pungdong.payment;

/**
 * 결제 <b>승인 시도</b>의 상태 — 환불의 {@link RefundStatus} 와 대칭.
 *
 * <ul>
 *   <li>{@code ATTEMPTED} — PG 승인 호출 <b>직전</b> 선기록(별도 트랜잭션 커밋). 이 상태로 남아 있으면
 *       "청구됐는지 모름"(전송 실패·프로세스 급사) = 대사 대상이며, 그 주문의 재승인을 막는다(이중청구 방지).</li>
 *   <li>{@code APPROVED} — PG 가 승인함(pgTransactionId 확보). <b>청구 사실의 durable 한 기록</b> — 이후 주문/회차
 *       확정(outer 트랜잭션)이 롤백돼도 이 행은 남아, 재시도가 <b>재청구 없이</b> 그 결과로 전진 확정한다.</li>
 *   <li>{@code FAILED} — PG 가 승인을 거절함(금액 불일치·인증 실패 등). 재시도 가능.</li>
 * </ul>
 */
public enum ApprovalStatus {
    ATTEMPTED,
    APPROVED,
    FAILED
}
