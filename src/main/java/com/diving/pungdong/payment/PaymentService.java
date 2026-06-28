package com.diving.pungdong.payment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.payment.dto.PaymentConfirmResponse;
import com.diving.pungdong.payment.dto.PaymentPrepareResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 결제 — 학생 측(준비/승인). 토스 결제위젯 v2 흐름의 BE. enrollment "수락 → 결제 → 확정" 의 결제 단계.
 * 다회차: 결제 단위는 <b>회차(EnrollmentRound)</b> — API 의 {@code enrollmentId} 는 회차 id 다.
 *
 * <p><b>보안 핵심</b>: 금액은 클라이언트를 신뢰하지 않는다. {@link #prepare}가 서버에서 권위 금액을 재계산해
 * {@link PaymentOrder}에 박고, {@link #confirm}은 클라이언트가 보낸 amount 가 그 값과 같을 때만 토스 승인을
 * 호출한다(토스도 같은 금액 → 위젯 결제액과 다르면 거절). 시크릿 키는 BE 밖으로 안 나간다.
 *
 * <p><b>권위 금액</b> = (첫 만남 회차면 수강료) + 부대비용(입장료+장비+추가세션비). 수강료는 enrollment 스냅샷
 * 고정(2026-06-28 — 환불 정산을 위해 라이브 재계산 폐기), 1회차에 전액, 2회차~ 는 부대비용만.
 */
// 명시적 빈 이름 — 레거시 com.diving.pungdong.service.PaymentService(죽은 예약 플로우)와 단순명이 같아
// 컴포넌트 스캔 시 기본 빈 이름("paymentService")이 충돌하기 때문. 주입은 타입으로(둘은 다른 타입).
@Service("enrollmentPaymentService")
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentOrderJpaRepo orderRepo;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final TossPaymentClient tossClient;
    private final OrderNoFormatter orderNoFormatter;

    /** 위젯 로드용 공개 클라이언트 키 — FE 에 그대로 내려준다(공개값). */
    private final String clientKey;

    public PaymentService(PaymentOrderJpaRepo orderRepo, EnrollmentRoundJpaRepo roundRepo,
                          TossPaymentClient tossClient, OrderNoFormatter orderNoFormatter,
                          @Value("${pungdong.payment.toss.client-key:}") String clientKey) {
        this.orderRepo = orderRepo;
        this.roundRepo = roundRepo;
        this.tossClient = tossClient;
        this.orderNoFormatter = orderNoFormatter;
        this.clientKey = clientKey;
    }

    /**
     * 결제 준비 — 수락된(PAYMENT_PENDING) 회차에 대해 권위 금액을 재계산하고 READY 주문을 만든다(멱등 —
     * 이미 READY 주문이 있으면 재사용, 금액 변동 시 갱신). FE 가 이 응답으로 위젯을 띄운다. {@code roundId} = 회차 id.
     */
    @Transactional
    public PaymentPrepareResponse prepare(Account student, Long roundId) {
        EnrollmentRound r = requirePayable(student, roundId);
        int amount = authoritativeAmount(r);

        PaymentOrder order = orderRepo.findByEnrollmentRoundIdAndStatus(roundId, PaymentStatus.READY)
                .orElseGet(() -> orderRepo.save(PaymentOrder.builder()
                        .orderId(newOrderId(roundId))
                        .enrollmentRound(r)
                        .amount(amount)
                        .orderName(orderName(r))
                        .status(PaymentStatus.READY)
                        .createdAt(LocalDateTime.now())
                        .build()));
        if (order.getAmount() != amount) { // 스냅샷이 그새 갱신됐으면 권위 금액 갱신
            order.setAmount(amount);
            order.setOrderName(orderName(r));
            order.setUpdatedAt(LocalDateTime.now());
        }
        return PaymentPrepareResponse.of(order, orderNoFormatter.format(order.getId()), clientKey, customerKey(student));
    }

    /**
     * 결제 승인 — 소유·상태·금액 검증 후 토스 {@code /v1/payments/confirm} 호출. DONE 이면 주문을 DONE 으로,
     * 회차를 CONFIRMED 로 확정. 멱등 — 이미 DONE 인 주문은 그대로 성공 반환(confirm 재호출/새로고침 대비).
     */
    @Transactional
    public PaymentConfirmResponse confirm(Account student, String paymentKey, String orderId, int amount) {
        PaymentOrder order = orderRepo.findByOrderId(orderId).orElseThrow(ResourceNotFoundException::new);
        EnrollmentRound r = order.getEnrollmentRound();
        Account owner = r == null || r.getEnrollment() == null ? null : r.getEnrollment().getStudent();
        if (owner == null || !owner.getId().equals(student.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 주문 — 존재 숨김
        }
        if (order.getStatus() == PaymentStatus.DONE) {
            return PaymentConfirmResponse.of(order, orderNoFormatter.format(order.getId())); // 멱등 — 이미 승인됨
        }
        if (order.getStatus() != PaymentStatus.READY) {
            throw new BadRequestException(); // 취소/실패 주문은 승인 불가
        }
        if (order.getAmount() != amount) {
            throw new BadRequestException(); // 클라이언트 금액이 서버 권위 금액과 불일치
        }
        if (r.getStatus() != EnrollmentStatus.PAYMENT_PENDING) {
            throw new BadRequestException(); // 결제 대기 상태가 아님(이미 확정/취소 등)
        }

        TossPaymentClient.TossConfirmResult result = tossClient.confirm(paymentKey, orderId, order.getAmount());
        if (!"DONE".equals(result.status())) {
            throw new BadRequestException(); // 토스 승인 미완
        }

        order.setStatus(PaymentStatus.DONE);
        order.setPaymentKey(paymentKey);
        order.setMethod(result.method());
        order.setApprovedAt(result.approvedAt());
        order.setUpdatedAt(LocalDateTime.now());
        r.setStatus(EnrollmentStatus.CONFIRMED); // 결제 완료 = 확정 (pay-first: 강사는 이후 수영장 예약)
        return PaymentConfirmResponse.of(order, orderNoFormatter.format(order.getId()));
    }

    /* ─── helpers ─── */

    /** 내 회차이고 결제 대기 상태여야 결제 가능. 없음/남의 것 = 404, 결제대기 아님 = 400. */
    private EnrollmentRound requirePayable(Account student, Long roundId) {
        EnrollmentRound r = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        Account owner = r.getEnrollment() == null ? null : r.getEnrollment().getStudent();
        if (owner == null || !owner.getId().equals(student.getId())) {
            throw new ResourceNotFoundException();
        }
        if (r.getStatus() != EnrollmentStatus.PAYMENT_PENDING) {
            throw new BadRequestException(); // 수락(결제 대기) 상태에서만 결제
        }
        return r;
    }

    /** 권위 금액(원) = (첫 만남이면 수강료 스냅샷) + 부대비용 스냅샷. 회차 단위. */
    private int authoritativeAmount(EnrollmentRound r) {
        return r.chargeTotal();
    }

    private String orderName(EnrollmentRound r) {
        var course = r.getEnrollment() == null ? null : r.getEnrollment().getCourse();
        String title = course == null ? "수강" : course.getTitle();
        return title + " (" + r.getRoundIndex() + "회차)";
    }

    /** 토스 주문번호 — 6~64자 [A-Za-z0-9-_]. 회차 식별 + UUID 로 유일성. */
    private String newOrderId(Long roundId) {
        return "rnd-" + roundId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 위젯 customerKey — 계정 식별(내부 id, PII 아님). 위젯이 요구하는 안정 키. */
    private String customerKey(Account student) {
        return "cust-" + student.getId();
    }
}
