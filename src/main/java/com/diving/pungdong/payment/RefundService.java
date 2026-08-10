package com.diving.pungdong.payment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.payment.dto.RefundQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 환불 — 학생 측(수강 종료 = 남은 회차 환불). {@link RefundCalculator} 로 회차별 환불액을 산정하고, 수강료 몫은
 * <b>1회차 결제주문 부분취소</b>(수강료가 거기 있음), 부대 몫은 <b>각 회차 주문 부분취소</b>로 PG 에 취소 요청한다.
 * 그 후 활성·미완료 회차를 모두 CANCELLED + 좌석 해제. PG 선택은 {@link PaymentGatewayRegistry}(주문에 박제된 provider 기준).
 *
 * <p>{@code enrollmentId} = 수강(컨테이너) id. 회차별 단건 환불이 아니라 <b>수강 단위 종료</b> — 액션매트릭스의
 * 진행 중 "환불신청". 환불율·정책은 {@link RefundCalculator} / docs/features/payment.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefundService {

    private final EnrollmentJpaRepo enrollmentRepo;
    private final PaymentOrderJpaRepo orderRepo;
    private final RefundOrderJpaRepo refundRepo;
    private final RefundCalculator calculator;
    private final PaymentGatewayRegistry gateways;
    private final SessionCleaner sessionCleaner;

    @Transactional
    public RefundQuote refundEnrollment(Account student, Long enrollmentId) {
        Enrollment e = enrollmentRepo.findById(enrollmentId).orElseThrow(ResourceNotFoundException::new);
        if (e.getStudent() == null || !e.getStudent().getId().equals(student.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 수강 — 존재 숨김
        }
        boolean hasActive = e.getRounds().stream().anyMatch(r -> r.getStatus().isActive() && !r.isDone());
        if (!hasActive) {
            throw new BadRequestException(); // 환불할 활성 회차 없음(전부 완료/취소)
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RefundQuote quote = calculator.quote(e, now.toLocalDate(), now);

        // 주문별 취소액 집계 — 수강료 몫 전부는 1회차 주문, 부대 몫은 각 회차 주문.
        Map<Long, Integer> orderRefund = new HashMap<>();
        int tuitionRefund = quote.getLines().stream().mapToInt(RefundQuote.Line::getTuitionPart).sum();
        EnrollmentRound firstRound = firstRegular(e);
        if (tuitionRefund > 0 && firstRound != null) {
            paidOrder(firstRound.getId()).ifPresent(o -> orderRefund.merge(o.getId(), tuitionRefund, Integer::sum));
        }
        for (RefundQuote.Line line : quote.getLines()) {
            if (line.getRoundId() != null && line.getExtraPart() > 0) {
                paidOrder(line.getRoundId()).ifPresent(o -> orderRefund.merge(o.getId(), line.getExtraPart(), Integer::sum));
            }
        }

        // (부분)취소 실행 + RefundOrder 기록 + 잔액 반영
        for (Map.Entry<Long, Integer> entry : orderRefund.entrySet()) {
            PaymentOrder order = orderRepo.findById(entry.getKey()).orElse(null);
            if (order == null || order.getPaymentKey() == null) {
                continue; // 안전: 주문 없거나 미승인이면 건너뜀
            }
            applyCancel(order, entry.getValue(), "수강 환불", now);
        }

        // 활성·미완료 회차 모두 CANCELLED + 빈 일정 해제(완료/이미취소는 유지)
        for (EnrollmentRound r : e.getRounds()) {
            if (r.getStatus().isActive() && !r.isDone()) {
                AvailabilitySession session = r.getAvailabilitySession();
                r.setStatus(EnrollmentStatus.CANCELLED);
                r.setRespondedAt(now);
                sessionCleaner.deleteIfEmpty(session);
            }
        }
        return quote;
    }

    /** 수강료가 든 주문의 주인 = 첫 정규회차(활성/완료). */
    private EnrollmentRound firstRegular(Enrollment e) {
        return e.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && Objects.equals(r.getRoundIndex(), 1)
                        && (r.getStatus().isActive() || r.isDone()))
                .findFirst().orElse(null);
    }

    private java.util.Optional<PaymentOrder> paidOrder(Long roundId) {
        return orderRepo.findByEnrollmentRoundIdAndStatus(roundId, PaymentStatus.DONE);
    }

    /**
     * 단일 회차 전액 환불 — <b>선결제 강사 거절/무응답 만료</b>가 이벤트로 호출한다({@code EnrollmentRefundListener}).
     * 학생 게이트 없음(시스템 트리거). 그 회차의 DONE 주문을 취소가능잔액 전액 취소 + {@code RefundOrder} 기록.
     *
     * <p>발행자(reject/expiry)의 <b>같은 트랜잭션</b>에서 동기 실행된다(REQUIRED) — PG 취소가 실패하면 예외가
     * 전파돼 상태변경까지 롤백된다(환불 성공해야 REJECTED/CANCELLED 도 커밋). 이미 환불됐거나 미결제면 no-op.
     */
    @Transactional
    public void refundRoundFully(Long roundId, String reason) {
        PaymentOrder order = paidOrder(roundId).orElse(null);
        if (order == null || order.getPaymentKey() == null) {
            return; // 결제 없음 = 환불할 것 없음(선결제 전 미결제 상태에서 만료된 경우 등)
        }
        // 취소가능 잔액 전액 — cancelAmount == 잔액 → 어댑터가 전체취소로 처리(이니시스 refund / 토스 cancel).
        applyCancel(order, order.refundableAmount(), reason, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 단일 회차 <b>부분</b> 환불 — 선결제 회차의 슬롯이 더 싼 슬롯으로 바뀌었을 때의 차액 반환
     * ({@code EnrollmentPartialRefundListener} 가 이벤트로 호출). 학생 게이트 없음(시스템 트리거).
     *
     * <p>{@link #refundRoundFully} 와 같은 동기·원자성 계약. 취소가능잔액을 넘지 않게 clamp 하며,
     * 미결제/잔액 0 이면 no-op(멱등).
     */
    @Transactional
    public void refundRoundPartially(Long roundId, int amount, String reason) {
        if (amount <= 0) {
            return;
        }
        PaymentOrder order = paidOrder(roundId).orElse(null);
        if (order == null || order.getPaymentKey() == null) {
            return; // 결제 없음 = 환불할 것 없음
        }
        applyCancel(order, amount, reason, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 환불 실행부 — 세 경로(수강 종료·회차 전액·차액)가 공유한다. PG 취소 → 이력({@code RefundOrder}) 기록 →
     * 주문 잔액 반영을 <b>한 트랜잭션</b>에서 한다.
     *
     * <ul>
     *   <li><b>clamp</b> — 취소가능 잔액({@code amount − refundedAmount})을 넘지 않는다. 이미 전액 환불됐으면 no-op(멱등).</li>
     *   <li><b>라우팅</b> — 취소는 <b>그 주문이 결제된 PG</b> 로 간다({@code order.provider}). 전역 설정으로 보내면
     *       PG 를 갈아탄 뒤 과거 주문 환불이 엉뚱한 곳으로 가 실패한다.</li>
     *   <li><b>잔액 반영</b> — {@code refundedAmount} 누적, 전액이 되면 {@code status = CANCELED}(부분은 {@code DONE} 유지).
     *       그래야 테이블만 보고 "정상 / 부분환불 / 전액환불"이 구분된다.</li>
     * </ul>
     *
     * <p>PG 취소가 실패하면 어댑터가 예외를 던져 <b>이 트랜잭션 전체가 롤백</b>된다 — 이력도 잔액도 남지 않는다
     * (돈-상태 원자성). 시도 이력을 별도 트랜잭션으로 남기는 건 후속(#202).
     *
     * @return 실제 취소된 금액(0 = 취소할 잔액 없음)
     */
    private int applyCancel(PaymentOrder order, int requested, String reason, OffsetDateTime now) {
        int refundable = order.refundableAmount();
        int amount = Math.min(requested, refundable);
        if (amount <= 0) {
            return 0;
        }
        log.info("[payment] 환불 요청 order={} provider={} 취소액={} 잔액={} tid={} 사유={}",
                order.getOrderId(), order.getProvider(), amount, refundable, order.getPaymentKey(), reason);
        gateways.forOrder(order.getProvider()).cancel(order.getPaymentKey(), amount, refundable, reason);
        refundRepo.save(RefundOrder.builder()
                .paymentOrder(order).amount(amount).reason(reason)
                .status(RefundStatus.DONE).createdAt(now).build());
        order.setRefundedAmount(order.getRefundedAmount() + amount);
        if (order.refundableAmount() <= 0) {
            order.setStatus(PaymentStatus.CANCELED); // 전액 환불 = 이 주문은 끝
        }
        order.setUpdatedAt(now);
        log.info("[payment] 환불 완료 order={} 취소액={} 누적환불={} 상태={}",
                order.getOrderId(), amount, order.getRefundedAmount(), order.getStatus());
        return amount;
    }
}
