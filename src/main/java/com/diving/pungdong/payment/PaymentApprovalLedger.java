package com.diving.pungdong.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 결제 승인 <b>원장</b> — 시도/결과를 <b>발행자(applyConfirm) 트랜잭션과 분리해서</b>({@code REQUIRES_NEW}) 기록한다.
 * 환불의 {@link RefundLedger} 와 대칭.
 *
 * <p><b>왜 별도 트랜잭션인가</b>(C1): 승인은 "PG 에 청구하라고 말하는 것"이라 <b>롤백되지 않는 외부 부수효과</b> 다.
 * 승인 사실을 발행자 트랜잭션에 묶으면, 그 트랜잭션이 뒤이어(주문 확정·회차 전이·좌석 재검증에서) 롤백될 때
 * <b>카드는 청구됐는데 DB 엔 흔적이 0</b> 이 된다. 그래서 PG 호출 직전 {@code ATTEMPTED} 를, 승인되면 {@code APPROVED}
 * (+pgTransactionId)를 즉시 커밋한다 — 확정이 롤백돼도 청구 사실은 남아 재시도가 <b>재청구 없이</b> 전진 확정한다.
 *
 * <p><b>왜 별도 빈인가</b>: self-invocation 이면 프록시를 안 거쳐 {@code REQUIRES_NEW} 가 무시된다. 조회 가드도
 * {@code REQUIRES_NEW} 라야 <b>다른 트랜잭션이 방금 커밋한 시도</b>를 본다(발행자 스냅샷에 갇히면 못 봐서 이중청구).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentApprovalLedger {

    private final PaymentOrderJpaRepo orderRepo;
    private final PaymentApprovalJpaRepo approvalRepo;

    /** PG 호출 <b>직전</b> 시도를 {@code ATTEMPTED} 로 선기록하고 즉시 커밋한다. 반환값 = 그 이력 id. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordAttempt(Long paymentOrderId, int amount, PaymentProvider provider, OffsetDateTime now) {
        PaymentOrder order = orderRepo.getReferenceById(paymentOrderId); // FK 만 필요 — 지연 참조
        PaymentApproval attempt = approvalRepo.save(PaymentApproval.builder()
                .paymentOrder(order).amount(amount).provider(provider)
                .status(ApprovalStatus.ATTEMPTED).attemptedAt(now).build());
        return attempt.getId();
    }

    /** PG 승인 성공 — {@code APPROVED} + 거래식별자/결제수단 확정(청구 사실의 durable 기록). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markApproved(Long attemptId, String pgTransactionId, String method, OffsetDateTime approvedAt,
                             OffsetDateTime now) {
        PaymentApproval a = approvalRepo.findById(attemptId).orElseThrow();
        a.setStatus(ApprovalStatus.APPROVED);
        a.setPgTransactionId(pgTransactionId);
        a.setMethod(method);
        a.setApprovedAt(approvedAt);
        a.setResolvedAt(now);
    }

    /** PG 가 승인을 거절 — {@code FAILED} + 진단정보. 재시도 가능(청구 안 됨). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long attemptId, String code, String message, OffsetDateTime now) {
        PaymentApproval a = approvalRepo.findById(attemptId).orElseThrow();
        a.setStatus(ApprovalStatus.FAILED);
        a.setResolvedAt(now);
        a.setFailureCode(truncate(code, 32));
        a.setFailureMessage(truncate(message, 255));
    }

    /**
     * 이미 승인(청구)된 시도 — 있으면 이전 확정이 롤백돼 주문이 READY 로 남은 것이므로, 재청구 없이 그 결과로
     * 전진 확정한다. {@code REQUIRES_NEW} 라 다른 트랜잭션이 방금 커밋한 {@code APPROVED} 도 본다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PaymentApproval> findApproved(Long paymentOrderId) {
        return approvalRepo.findByPaymentOrderIdAndStatus(paymentOrderId, ApprovalStatus.APPROVED).stream().findFirst();
    }

    /**
     * 결과 미확인 시도({@code ATTEMPTED} 잔존)가 있나 — 있으면 재승인을 막는다(카드가 이미 청구됐을 수 있어
     * 재호출이 이중청구가 된다). 사람이 PG 원장과 대사한 뒤 그 행을 확정해야 다시 흐른다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasUnresolvedApproval(Long paymentOrderId) {
        List<PaymentApproval> pending = approvalRepo.findByPaymentOrderIdAndStatus(paymentOrderId, ApprovalStatus.ATTEMPTED);
        if (!pending.isEmpty()) {
            log.error("[payment] 결과 미확인 승인 시도가 있어 재승인 차단 order={} 미확인={}건 — PG 원장 대사 필요(카드 청구됐을 수 있음)",
                    paymentOrderId, pending.size());
        }
        return !pending.isEmpty();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
