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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 환불 — 학생 측(수강 종료 = 남은 회차 환불). {@link RefundCalculator} 로 회차별 환불액을 산정하고, 수강료 몫은
 * <b>1회차 결제주문 부분취소</b>(수강료가 거기 있음), 부대 몫은 <b>각 회차 주문 부분취소</b>로 토스에 취소 요청한다.
 * 그 후 활성·미완료 회차를 모두 CANCELLED + 좌석 해제. stub/실연동은 {@link TossPaymentClient}(결제와 동일 패턴).
 *
 * <p>{@code enrollmentId} = 수강(컨테이너) id. 회차별 단건 환불이 아니라 <b>수강 단위 종료</b> — 액션매트릭스의
 * 진행 중 "환불신청". 환불율·정책은 {@link RefundCalculator} / docs/features/payment.md.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefundService {

    private final EnrollmentJpaRepo enrollmentRepo;
    private final PaymentOrderJpaRepo orderRepo;
    private final RefundOrderJpaRepo refundRepo;
    private final RefundCalculator calculator;
    private final TossPaymentClient tossClient;
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

        // 토스 (부분)취소 실행 + RefundOrder 기록
        for (Map.Entry<Long, Integer> entry : orderRefund.entrySet()) {
            PaymentOrder order = orderRepo.findById(entry.getKey()).orElse(null);
            if (order == null || order.getPaymentKey() == null) {
                continue; // 안전: 주문 없거나 미승인이면 건너뜀
            }
            int amount = Math.min(entry.getValue(), order.getAmount()); // 주문액 초과 방지
            if (amount <= 0) {
                continue;
            }
            tossClient.cancel(order.getPaymentKey(), amount, "수강 환불");
            refundRepo.save(RefundOrder.builder()
                    .paymentOrder(order).amount(amount).reason("수강 환불")
                    .status(RefundStatus.DONE).createdAt(now).build());
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
}
