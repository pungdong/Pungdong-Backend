package com.diving.pungdong.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 결제 <b>대사(reconciliation) 표면화</b> — 결과를 모르는 시도가 오래 남아 있으면 알린다.
 *
 * <p><b>왜 필요한가</b>(감사 지적): 승인({@code ATTEMPTED})·환불({@code REQUESTED})의 "결과 미확인" 행은
 * <b>카드가 청구/취소됐는지 모르는</b> 상태다. 원장(payment_approval·refund_order)에 남기는 것까지는 됐지만,
 * <b>그 행이 있다는 걸 사람이 알 방법이 없었다</b> — "사람이 PG 원장과 대사해 확정해야 흐른다"는데 대사할 게
 * 있는지조차 몰랐던 것. 이 스윕이 주기적으로 세어 ERROR 로 올려(알림·대시보드가 잡게) 그 공백을 메운다.
 *
 * <p>여긴 <b>탐지/알림</b>만 한다 — 자동으로 재승인/재환불하지 않는다(이중청구·이중환불 위험). 확정은 사람이 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliation {

    private final PaymentApprovalJpaRepo approvalRepo;
    private final RefundOrderJpaRepo refundRepo;

    /**
     * {@code olderThanMinutes} 넘게 결과 미확인으로 남은 승인/환불 시도를 세어 알린다. 반환 = 막힌 시도 총수.
     * 전송 성공 직후의 정상 시도(곧 확정될)를 오탐하지 않도록 유예 시간(cutoff) 뒤의 것만 본다.
     */
    @Transactional(readOnly = true)
    public int reportStuck(OffsetDateTime now, int olderThanMinutes) {
        OffsetDateTime cutoff = now.minusMinutes(olderThanMinutes);
        List<PaymentApproval> approvals = approvalRepo.findByStatusAndAttemptedAtBefore(ApprovalStatus.ATTEMPTED, cutoff);
        List<RefundOrder> refunds = refundRepo.findByStatusAndCreatedAtBefore(RefundStatus.REQUESTED, cutoff);

        if (!approvals.isEmpty()) {
            log.error("[reconciliation] 결과 미확인 승인 시도 {}건 — 카드 청구 여부 미상, PG 원장 대사 필요. approvalIds={}",
                    approvals.size(), ids(approvals, PaymentApproval::getId));
        }
        if (!refunds.isEmpty()) {
            log.error("[reconciliation] 결과 미확인 환불 시도 {}건 — 취소 여부 미상, PG 원장 대사 필요. refundIds={}",
                    refunds.size(), ids(refunds, RefundOrder::getId));
        }
        return approvals.size() + refunds.size();
    }

    private static <T> String ids(List<T> items, Function<T, Long> id) {
        return items.stream().limit(50).map(id).map(String::valueOf).collect(Collectors.joining(","));
    }
}
