package com.diving.pungdong.payment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRefs;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.PaymentGatewayException;
import com.diving.pungdong.global.advice.exception.RefundBlockedException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.notification.event.RefundCompletedEvent;
import com.diving.pungdong.payment.dto.RefundQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

    // 환불율은 세션일까지 남은 '일수'로 갈린다 — 세션일은 KST 운영 캘린더 기준이라 오늘 날짜도 KST 로 잡는다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EnrollmentJpaRepo enrollmentRepo;
    private final PaymentOrderJpaRepo orderRepo;
    private final RefundLedger ledger; // 시도/결과 기록 — 별도 트랜잭션(REQUIRES_NEW)
    private final RefundCalculator calculator;
    private final PaymentGatewayRegistry gateways;
    private final SessionCleaner sessionCleaner;
    private final EnrollmentRoundJpaRepo roundRepo;    // 환불 알림 좌표 조회
    private final ApplicationEventPublisher events; // 학생 요청 환불 완료 알림

    /**
     * 환불율 기준 '오늘' — 세션일이 KST 운영 캘린더라 instant 를 KST 날짜로 환산한다.
     * UTC 날짜로 쓰면 KST 00~09시 취소가 하루 밀려 환불 단계가 한 칸 유리해진다(당일 0% 가 전날 50% 로).
     */
    static LocalDate businessToday(OffsetDateTime instant) {
        return instant.atZoneSameInstant(KST).toLocalDate();
    }

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
        // 그레이스(결제 1h 내 100%) 기준 = 회차별 결제완료 시각(승인 주문 approvedAt 중 최소, 불변).
        // respondedAt(가변)이 아니라 이걸 써야 강사 수락·일정변경으로 그레이스가 리셋되지 않는다.
        Map<Long, OffsetDateTime> paidAtByRound = new HashMap<>();
        for (EnrollmentRound r : e.getRounds()) {
            paidOrders(r.getId()).stream()
                    .map(PaymentOrder::getApprovedAt).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .ifPresent(min -> paidAtByRound.put(r.getId(), min));
        }
        // now(instant) 는 UTC 로 두되(그레이스 창은 절대시각 비교라 무관), 환불율의 기준 '오늘'은 KST 날짜다.
        RefundQuote quote = calculator.quote(e, businessToday(now), now, paidAtByRound);

        // 주문별 취소액 집계 — 수강료 몫 전부는 1회차 주문, 부대 몫은 각 회차 주문.
        Map<Long, Integer> orderRefund = new HashMap<>();
        int tuitionRefund = quote.getLines().stream().mapToInt(RefundQuote.Line::getTuitionPart).sum();
        EnrollmentRound firstRound = firstRegular(e);
        if (tuitionRefund > 0 && firstRound != null) {
            spreadOverOrders(paidOrders(firstRound.getId()), tuitionRefund, orderRefund);
        }
        for (RefundQuote.Line line : quote.getLines()) {
            if (line.getRoundId() != null && line.getExtraPart() > 0) {
                spreadOverOrders(paidOrders(line.getRoundId()), line.getExtraPart(), orderRefund);
            }
        }

        // (부분)취소 실행 + RefundOrder 기록 + 잔액 반영
        // ⚠️ 알림 문구에 쓸 금액은 <b>계획액이 아니라 실제 반환액</b>이어야 한다 — applyCancel 은
        // 잔액으로 clamp 하고, 결과 미확인 시도가 있으면 아예 0 을 돌려주고 건너뛴다. 계획액으로
        // 문구를 만들면 "N원이 환불되었어요" 가 거짓이 될 수 있고, 그 문구는 알림함에 영구 보존된다.
        int refunded = 0;
        for (Map.Entry<Long, Integer> entry : orderRefund.entrySet()) {
            PaymentOrder order = orderRepo.findById(entry.getKey()).orElse(null);
            if (order == null || order.getPaymentKey() == null) {
                continue; // 안전: 주문 없거나 미승인이면 건너뜀
            }
            refunded += applyCancel(order, entry.getValue(), "수강 환불", now);
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
        // 환불 완료 알림 — <b>학생이 직접 요청한 환불</b>에만 발행한다(2026-08-14 사용자 결정).
        // 거절·만료로 인한 자동환불(refundRoundFully/Partially)에는 걸지 않는다: 그쪽은
        // ENROLLMENT_REJECTED / ENROLLMENT_EXPIRED body 가 이미 환불을 안내해서 같은 사건에
        // 알림이 2건 연속 가면 소음이다.
        // 실제로 한 푼도 안 나갔으면(전액 clamp/미확인 스킵) 알리지 않는다 — 0원 환불 알림은 거짓이다.
        if (refunded > 0) {
            events.publishEvent(RefundCompletedEvent.builder()
                    .studentAccountId(student.getId())
                    .courseId(e.getCourse() == null ? null : e.getCourse().getId())
                    .enrollmentId(e.getId())
                    .roundId(firstRound == null ? null : firstRound.getId())
                    .courseTitle(e.getCourse() == null || e.getCourse().getTitle() == null
                            ? "수업" : e.getCourse().getTitle())
                    .amount(refunded)
                    .build());
        }
        return quote;
    }

    /**
     * 회차 환불액을 그 회차의 주문들에 <b>최신 주문부터</b> 배분한다(차액 결제분을 먼저 되돌림).
     * 주문별 잔액을 넘지 않으며, 회차 순액보다 큰 요청은 순액까지만 배분된다.
     */
    private void spreadOverOrders(List<PaymentOrder> orders, int amount, Map<Long, Integer> out) {
        int remaining = Math.min(amount, roundRefundable(orders));
        for (int i = orders.size() - 1; i >= 0 && remaining > 0; i--) {
            PaymentOrder order = orders.get(i);
            int take = Math.min(remaining, order.refundableAmount());
            if (take <= 0) {
                continue;
            }
            out.merge(order.getId(), take, Integer::sum);
            remaining -= take;
        }
    }

    /** 수강료가 든 주문의 주인 = 첫 정규회차(활성/완료). */
    private EnrollmentRound firstRegular(Enrollment e) {
        return e.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && Objects.equals(r.getRoundIndex(), 1)
                        && (r.getStatus().isActive() || r.isDone()))
                .findFirst().orElse(null);
    }

    /**
     * 그 회차의 <b>승인된 주문들</b>(결제 순서). 회차당 여러 건일 수 있다 — 원결제 + 일정 변경 차액 결제.
     *
     * <p>환불 <b>실행</b>은 주문 단위여야 한다(PG 취소 전문에 그 주문의 {@code paymentKey} 를 실어야 하므로).
     * 회차는 그 위의 <b>집계</b> 단위다: {@code 회차 순액 = Σ(승인액) − Σ(환불액)}.
     */
    private List<PaymentOrder> paidOrders(Long roundId) {
        return orderRepo.findByEnrollmentRoundIdAndStatusOrderByIdAsc(roundId, PaymentStatus.DONE);
    }

    /** 그 회차에 남아 있는 결제 순액 = Σ(승인액 − 환불액). 취소가능 잔액의 회차 합. */
    private int roundRefundable(List<PaymentOrder> orders) {
        return orders.stream().mapToInt(PaymentOrder::refundableAmount).sum();
    }

    /**
     * 단일 회차 전액 환불 — <b>선결제 강사 거절/무응답 만료</b>가 이벤트로 호출한다({@code EnrollmentRefundListener}).
     * 학생 게이트 없음(시스템 트리거). 그 회차의 <b>승인 주문들을 각각</b> 취소가능잔액 전액 취소 + 이력 기록
     * (회차당 주문이 여러 건일 수 있다 — 원결제 + 일정 변경 차액 결제).
     *
     * <p>발행자(reject/expiry)의 <b>같은 트랜잭션</b>에서 동기 실행된다(REQUIRED) — PG 취소가 실패하면 예외가
     * 전파돼 상태변경까지 롤백된다(환불 성공해야 REJECTED/CANCELLED 도 커밋). 이미 환불됐거나 미결제면 no-op.
     *
     * <p>단 <b>환불 기록 자체는 별도 트랜잭션</b>({@link RefundLedger})이라 롤백에 휩쓸리지 않는다 — 실패는
     * {@code FAILED} 로, 성공 후 상태전이가 깨지면 환불은 확정된 채 남아 재시도가 no-op 이 된다(이중환불 방지).
     */
    @Transactional
    public void refundRoundFully(Long roundId, String reason) {
        refundRoundFully(roundId, reason, false);
    }

    /**
     * @param studentInitiated 학생이 스스로 취소해서 생긴 환불인가. {@code true} 면 <b>환불 완료 알림</b>을
     *                         발행한다 — 거절·만료는 그쪽 알림 body 가 이미 환불을 안내하므로 발행하지
     *                         않는다(사용자 결정: 같은 사건에 알림 2건은 소음).
     *                         금액은 <b>실제 반환액</b>이다({@link #applyCancel} 이 잔액으로 clamp 하고,
     *                         결과 미확인 시도가 있으면 0 을 돌려주고 건너뛴다).
     */
    @Transactional
    public void refundRoundFully(Long roundId, String reason, boolean studentInitiated) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 그 회차의 승인 주문을 모두 각각 잔액 전액 취소 — cancelAmount == 그 주문 잔액이라 어댑터가 전체취소로 처리.
        int refunded = 0;
        for (PaymentOrder order : paidOrders(roundId)) {
            if (order.getPaymentKey() == null) {
                continue; // 안전: 미승인 주문은 건너뜀
            }
            refunded += applyCancel(order, order.refundableAmount(), reason, now);
        }
        if (studentInitiated && refunded > 0) {
            publishRefundCompleted(roundId, refunded);
        }
    }

    /** 회차 하나 기준 환불 완료 알림. 좌표를 못 만들면(수신자 null) 발행을 건너뛴다 — 알림 때문에 환불이 롤백되면 안 된다. */
    private void publishRefundCompleted(Long roundId, int refunded) {
        EnrollmentRound round = roundRepo.findById(roundId).orElse(null);
        EnrollmentRefs refs = EnrollmentRefs.of(round);
        if (!refs.canNotifyStudent()) {
            return;
        }
        events.publishEvent(RefundCompletedEvent.builder()
                .studentAccountId(refs.getStudentAccountId())
                .courseId(refs.getCourseId())
                .enrollmentId(refs.getEnrollmentId())
                .roundId(refs.getRoundId())
                .courseTitle(refs.courseTitleOrFallback())
                .amount(refunded)
                .build());
    }

    /**
     * 단일 회차 <b>부분</b> 환불 — 선결제 회차의 슬롯이 더 싼 슬롯으로 바뀌었을 때의 차액 반환
     * ({@code EnrollmentPartialRefundListener} 가 이벤트로 호출). 학생 게이트 없음(시스템 트리거).
     *
     * <p>{@link #refundRoundFully} 와 같은 동기·원자성 계약(기록은 {@link RefundLedger} 별도 트랜잭션).
     * <b>회차 순액</b>(Σ 주문 잔액)을 넘지 않게 clamp 하며, 미결제/잔액 0 이면 no-op(멱등).
     * 여러 주문에 걸치면 <b>최신 주문부터</b> 뺀다 — 차액 결제분을 먼저 되돌리는 게 직관적이다.
     */
    @Transactional
    public void refundRoundPartially(Long roundId, int amount, String reason) {
        if (amount <= 0) {
            return;
        }
        List<PaymentOrder> orders = paidOrders(roundId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int remaining = Math.min(amount, roundRefundable(orders));
        // 최신 주문부터 뺀다 — 차액 결제분을 먼저 되돌리는 게 직관적이다(원결제가 부분환불된 것처럼 보이지 않는다).
        for (int i = orders.size() - 1; i >= 0 && remaining > 0; i--) {
            PaymentOrder order = orders.get(i);
            if (order.getPaymentKey() == null) {
                continue;
            }
            remaining -= applyCancel(order, remaining, reason, now);
        }
    }

    /**
     * 환불 실행부 — 세 경로(수강 종료·회차 전액·차액)가 공유한다.
     * <b>시도 선기록 → PG 취소 → 결과 확정</b> 순으로, 기록은 모두 {@link RefundLedger}(별도 트랜잭션)가 맡는다.
     *
     * <ul>
     *   <li><b>clamp</b> — 취소가능 잔액({@code amount − refundedAmount})을 넘지 않는다. 이미 전액 환불됐으면 no-op(멱등).</li>
     *   <li><b>대사 가드</b> — 결과를 모르는 시도({@code REQUESTED} 잔존)가 있으면 {@link RefundBlockedException} 을 던져
     *       <b>발행자(거절·취소·만료)까지 롤백</b>시킨다(C2). 조용히 건너뛰면 회차만 끝나고 돈이 남는다 — "환불 못 하면
     *       상태 전이도 확정 안 함". 사람이 PG 원장과 대사해 그 행을 확정하면(또는 만료 스윕이 재시도하면) 다시 흐른다.</li>
     *   <li><b>취소 확정 확인</b> — 어댑터의 {@code canceled} 가 false 면(PG 미확정) {@code FAILED} 로 남기고 던진다(H-2) —
     *       "환불했다고 기록되나 실제론 안 됨"을 막는다.</li>
     *   <li><b>라우팅</b> — 취소는 <b>그 주문이 결제된 PG</b> 로 간다({@code order.provider}). 전역 설정으로 보내면
     *       PG 를 갈아탄 뒤 과거 주문 환불이 엉뚱한 곳으로 가 실패한다.</li>
     * </ul>
     *
     * <p><b>실패 시</b>: 어댑터가 던진 예외를 {@code FAILED}(+PG 코드/사유)로 <b>남기고 다시 던진다</b> — 발행자
     * (거절·만료·취소) 트랜잭션은 롤백되지만 <b>실패 이력은 남는다</b>. 재시도·대사의 근거.
     *
     * <p><b>성공 후 발행자가 롤백되면</b>: 환불 기록과 잔액은 이미 커밋돼 남는다(현실과 일치 — PG 는 취소했다).
     * 다음 재시도는 줄어든 잔액을 보고 no-op 하므로 <b>이중환불이 되지 않는다</b>. 상태 전이만 다시 시도된다.
     *
     * @return 실제 취소된 금액(0 = 취소할 잔액 없음 / 대사 대기)
     */
    private int applyCancel(PaymentOrder order, int requested, String reason, OffsetDateTime now) {
        int refundable = order.refundableAmount();
        int amount = Math.min(requested, refundable);
        if (amount <= 0) {
            return 0; // 취소할 잔액 없음(이미 전액 환불) — no-op 멱등
        }
        if (ledger.hasUnresolvedAttempt(order.getId())) {
            // 결과 미확인 시도가 있어 자동 환불 불가. 조용히 넘기면(옛 return 0) 발행자(거절·취소·만료)가 그대로
            // 커밋돼 회차는 끝나는데 돈만 남는다(C2). 발행자를 롤백시켜 "환불 못 하면 상태 전이도 확정 안 함"을 강제.
            throw new RefundBlockedException("결과 미확인 환불 시도가 있어 환불을 진행할 수 없음 order=" + order.getOrderId());
        }
        // 위 가드는 락 없는 pre-check 라 near-simultaneous 두 환불이 둘 다 통과할 수 있다(H-1). 실제 원자 차단은
        // uk_refund_order_inflight(V26) — 주문당 REQUESTED 1개. 동시 두 번째 recordAttempt 는 유니크 위반으로
        // 여기서 걸러 PG 취소까지 못 가고 발행자를 롤백시킨다(가드가 던지는 것과 같은 결과 — 이중환불 방지).
        Long attemptId;
        try {
            attemptId = ledger.recordAttempt(order.getId(), amount, reason, now); // 별도 tx — 즉시 커밋
        } catch (DataIntegrityViolationException dup) {
            throw new RefundBlockedException("동시 환불 시도 충돌 — 다른 환불이 진행 중 order=" + order.getOrderId());
        }
        log.info("[payment] 환불 요청 order={} provider={} 취소액={} 잔액={} tid={} 사유={} attempt={}",
                order.getOrderId(), order.getProvider(), amount, refundable, order.getPaymentKey(), reason, attemptId);
        PaymentGateway.CancelResult result;
        try {
            result = gateways.forOrder(order.getProvider()).cancel(order.getPaymentKey(), amount, refundable, reason);
        } catch (PaymentGatewayException e) {
            ledger.markFailed(attemptId, e.getCode(), e.getDetail(), OffsetDateTime.now(ZoneOffset.UTC));
            throw e;
        } catch (RuntimeException e) {
            // 전송 실패(타임아웃·파싱 등) — PG 가 취소를 처리했는지 <b>모른다</b>. REQUESTED 로 남겨 대사 대상으로 둔다.
            log.error("[payment] 환불 전송 실패 order={} attempt={} — 결과 미확인(REQUESTED 유지, 대사 필요)",
                    order.getOrderId(), attemptId, e);
            throw e;
        }
        if (!result.canceled()) {
            // PG 가 2xx 를 줬지만 취소를 확정하지 않았다(비동기·미지원 상태 등). 반환값을 안 보고 markDone 하면
            // "환불했다고 기록되나 실제론 안 됨"이 돼 재환불도 clamp 에 막혀 영구 미환불이 된다 — FAILED 로 남기고 롤백(H-2).
            ledger.markFailed(attemptId, result.rawStatus(), "PG 가 취소를 확정하지 않음", OffsetDateTime.now(ZoneOffset.UTC));
            throw new PaymentGatewayException(result.rawStatus(), "취소 미확정");
        }
        ledger.markDone(attemptId, order.getId(), amount, OffsetDateTime.now(ZoneOffset.UTC));
        log.info("[payment] 환불 완료 order={} 취소액={} attempt={}", order.getOrderId(), amount, attemptId);
        return amount;
    }
}
