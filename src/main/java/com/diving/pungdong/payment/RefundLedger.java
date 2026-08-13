package com.diving.pungdong.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 환불 <b>원장</b> — 시도/결과를 <b>발행자 트랜잭션과 분리해서</b>({@code REQUIRES_NEW}) 기록한다.
 *
 * <p><b>왜 별도 트랜잭션인가</b>: 환불은 "PG 에 돈을 돌려주라고 말하는 것"이라 <b>롤백되지 않는 외부 부수효과</b> 다.
 * 기록을 발행자(강사 거절·만료 sweep·학생 취소) 트랜잭션에 묶어두면, 그 트랜잭션이 나중에 롤백될 때
 * <b>PG 에는 취소가 나갔는데 우리 DB 엔 흔적이 없는</b> 상태가 된다 — 재시도가 이중환불이 되고, 대사도 불가능하다.
 * 그래서 이 클래스의 메서드는 모두 자기 트랜잭션에서 즉시 커밋된다.
 *
 * <p><b>왜 별도 빈인가</b>: 같은 클래스 안에서 부르면 Spring 프록시를 안 거쳐 {@code REQUIRES_NEW} 가 무시된다
 * (self-invocation). {@link RefundService} 가 이 빈을 주입받아 호출해야 전파 속성이 실제로 적용된다.
 *
 * <p><b>잔액 캐시도 여기서</b>: {@code PaymentOrder.refundedAmount} 를 {@code DONE} 기록과 <b>같은 트랜잭션</b>에서
 * 올린다. 원장과 캐시가 함께 커밋되므로 둘이 어긋날 창이 없고, 발행자가 롤백돼도 다음 재시도는 줄어든 잔액을 봐서
 * <b>이중환불이 되지 않는다</b>(잔액 0 이면 no-op).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundLedger {

    private final PaymentOrderJpaRepo orderRepo;
    private final RefundOrderJpaRepo refundRepo;

    /**
     * PG 호출 <b>직전</b> 시도를 {@code REQUESTED} 로 선기록하고 즉시 커밋한다. 반환값 = 그 이력 id.
     * 이 커밋 이후 프로세스가 죽어도 "시도했다"는 사실은 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordAttempt(Long paymentOrderId, int amount, String reason, OffsetDateTime now) {
        PaymentOrder order = orderRepo.getReferenceById(paymentOrderId); // FK 만 필요 — 지연 참조로 충분
        RefundOrder attempt = refundRepo.save(RefundOrder.builder()
                .paymentOrder(order).amount(amount).reason(reason)
                .status(RefundStatus.REQUESTED).createdAt(now).build());
        return attempt.getId();
    }

    /** PG 승인 성공 — 이력을 {@code DONE} 으로 확정하고 주문 잔액(캐시)을 같이 올린다. 전액이면 주문을 {@code CANCELED} 로. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(Long attemptId, Long paymentOrderId, int amount, OffsetDateTime now) {
        RefundOrder attempt = refundRepo.findById(attemptId).orElseThrow();
        attempt.setStatus(RefundStatus.DONE);
        attempt.setCompletedAt(now);

        PaymentOrder order = orderRepo.findById(paymentOrderId).orElseThrow();
        order.setRefundedAmount(order.getRefundedAmount() + amount);
        if (order.refundableAmount() <= 0) {
            order.setStatus(PaymentStatus.CANCELED); // 전액 환불 = 이 주문은 끝
        }
        order.setUpdatedAt(now);
    }

    /** PG 가 거절 — 이력을 {@code FAILED} + 진단정보로 확정한다(잔액은 그대로). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long attemptId, String code, String message, OffsetDateTime now) {
        RefundOrder attempt = refundRepo.findById(attemptId).orElseThrow();
        attempt.setStatus(RefundStatus.FAILED);
        attempt.setCompletedAt(now);
        attempt.setFailureCode(truncate(code, 32));
        attempt.setFailureMessage(truncate(message, 255));
    }

    /**
     * 결과를 모르는 시도({@code REQUESTED} 잔존)가 있나 — 있으면 그 주문은 <b>자동 환불을 더 시도하지 않는다</b>.
     * PG 에 이미 취소가 반영됐을 수 있어(전송 실패·프로세스 급사) 재시도가 이중환불이 될 수 있기 때문. 사람이
     * PG 원장과 대사한 뒤 그 행을 {@code DONE}/{@code FAILED} 로 확정해야 다시 흐른다.
     *
     * <p><b>REQUIRES_NEW</b>: 발행자 트랜잭션에 조인하면 그 스냅샷에 갇혀 <b>다른 트랜잭션이 방금 커밋한
     * {@code REQUESTED}</b>(동시 환불 시도)를 못 본다 — 가드가 무력해져 이중환불이 난다. 별도 트랜잭션으로
     * 최신 커밋을 읽는다({@code recordAttempt} 도 REQUIRES_NEW 라 즉시 커밋되므로 이 조회로 보인다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasUnresolvedAttempt(Long paymentOrderId) {
        List<RefundOrder> pending = refundRepo.findByPaymentOrderIdAndStatus(paymentOrderId, RefundStatus.REQUESTED);
        if (!pending.isEmpty()) {
            log.warn("[payment] 결과 미확인 환불 시도가 있어 자동 환불을 건너뜀 order={} 미확인={}건 — PG 원장 대사 필요",
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
