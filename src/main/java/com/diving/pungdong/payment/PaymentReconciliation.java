package com.diving.pungdong.payment;

import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    private final EnrollmentRoundJpaRepo roundRepo;
    private final PaymentOrderJpaRepo orderRepo;

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

    /**
     * <b>금액 정합 대사(M1)</b> — 결제완료/확정 회차의 <b>순액(Σ승인−Σ환불) 이 {@code chargeTotal()} 과 같은지</b> 검증하고
     * 어긋난 회차를 ERROR 로 표면화한다. 반환 = 어긋난 회차 수.
     *
     * <p><b>왜</b>(감사 지적 M1): "그 회차에 남은 결제 순액 == chargeTotal()" 이 결제 도메인의 금액 불변식인데,
     * 이를 사후 검증하는 곳이 없었다. 부분환불·차액결제·슬롯변경의 버그나 PG-DB 불일치가 생기면 조용히 어긋난 채 남는다.
     * {@link #reportStuck} 이 "결과 미확인 시도"를 보는 것과 짝을 이뤄, 이건 "결과는 확정됐는데 금액이 안 맞는" 드리프트를 잡는다.
     *
     * <p><b>오탐 방지</b>: (1) <b>결제완료(ACCEPT_PENDING)·확정(CONFIRMED)</b> 회차만 — 미결제(PENDING)는 아직 안 냈고,
     * 취소·거절은 순액 0 이 정상. (2) {@code respondedAt < cutoff} 로 방금 전이한 건 제외. (3) <b>결과 미확인 환불(REQUESTED)</b>
     * 이 걸린 주문이 있는 회차는 전이 중이라 건너뛴다(그건 {@link #reportStuck} 이 이미 표면화). 남는 건 "정착됐는데 안 맞는" 진짜 드리프트.
     */
    @Transactional(readOnly = true)
    public int reportAmountMismatch(OffsetDateTime now, int olderThanMinutes) {
        OffsetDateTime cutoff = now.minusMinutes(olderThanMinutes);
        List<EnrollmentRound> rounds = roundRepo.findByStatusInAndRespondedAtBefore(
                List.of(EnrollmentStatus.ACCEPT_PENDING, EnrollmentStatus.CONFIRMED), cutoff);
        List<Long> mismatched = new ArrayList<>();
        for (EnrollmentRound r : rounds) {
            List<PaymentOrder> done = orderRepo.findByEnrollmentRoundIdAndStatusOrderByIdAsc(r.getId(), PaymentStatus.DONE);
            boolean refundInFlight = done.stream().anyMatch(
                    o -> !refundRepo.findByPaymentOrderIdAndStatus(o.getId(), RefundStatus.REQUESTED).isEmpty());
            if (refundInFlight) {
                continue; // 전이 중 — 오탐 방지(reportStuck 이 REQUESTED 를 이미 표면화)
            }
            int netPaid = done.stream().mapToInt(PaymentOrder::refundableAmount).sum(); // amount − refundedAmount 합
            if (netPaid != r.chargeTotal()) {
                mismatched.add(r.getId());
            }
        }
        if (!mismatched.isEmpty()) {
            log.error("[reconciliation] 회차 순액 ≠ chargeTotal {}건 — 청구/환불 드리프트, PG·DB 대사 필요. roundIds={}",
                    mismatched.size(), mismatched.stream().limit(50).map(String::valueOf).collect(Collectors.joining(",")));
        }
        return mismatched.size();
    }

    private static <T> String ids(List<T> items, Function<T, Long> id) {
        return items.stream().limit(50).map(id).map(String::valueOf).collect(Collectors.joining(","));
    }
}
