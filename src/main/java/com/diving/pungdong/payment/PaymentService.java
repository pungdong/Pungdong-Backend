package com.diving.pungdong.payment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
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
 *
 * <p><b>보안 핵심</b>: 금액은 클라이언트를 신뢰하지 않는다. {@link #prepare}가 서버에서 권위 금액을 재계산해
 * {@link PaymentOrder}에 박고, {@link #confirm}은 클라이언트가 보낸 amount 가 그 값과 같을 때만 토스 승인을
 * 호출한다(토스도 같은 금액으로 승인 → 위젯 결제액과 다르면 토스가 거절). 시크릿 키는 BE 밖으로 안 나간다.
 *
 * <p><b>권위 금액</b> = 수강료(코스 라이브 가격) + 입장료(신청 스냅샷) + 장비(신청 스냅샷). 가장 잘 변하는
 * 수강료만 라이브로 다시 읽는다(입장료/장비 venue 블록 live 재도출은 후속).
 */
// 명시적 빈 이름 — 레거시 com.diving.pungdong.service.PaymentService(죽은 예약 플로우)와 단순명이 같아
// 컴포넌트 스캔 시 기본 빈 이름("paymentService")이 충돌하기 때문. 주입은 타입으로(둘은 다른 타입).
@Service("enrollmentPaymentService")
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentOrderJpaRepo orderRepo;
    private final EnrollmentJpaRepo enrollmentRepo;
    private final TossPaymentClient tossClient;

    /** 위젯 로드용 공개 클라이언트 키 — FE 에 그대로 내려준다(공개값). */
    private final String clientKey;

    public PaymentService(PaymentOrderJpaRepo orderRepo, EnrollmentJpaRepo enrollmentRepo,
                          TossPaymentClient tossClient,
                          @Value("${pungdong.payment.toss.client-key:}") String clientKey) {
        this.orderRepo = orderRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.tossClient = tossClient;
        this.clientKey = clientKey;
    }

    /**
     * 결제 준비 — 수락된(PAYMENT_PENDING) 신청에 대해 권위 금액을 재계산하고 READY 주문을 만든다(멱등 —
     * 이미 READY 주문이 있으면 재사용, 금액 변동 시 갱신). FE 가 이 응답으로 위젯을 띄운다.
     */
    @Transactional
    public PaymentPrepareResponse prepare(Account student, Long enrollmentId) {
        Enrollment e = requirePayable(student, enrollmentId);
        int amount = authoritativeAmount(e);

        PaymentOrder order = orderRepo.findByEnrollmentIdAndStatus(enrollmentId, PaymentStatus.READY)
                .orElseGet(() -> orderRepo.save(PaymentOrder.builder()
                        .orderId(newOrderId(enrollmentId))
                        .enrollment(e)
                        .amount(amount)
                        .orderName(orderName(e))
                        .status(PaymentStatus.READY)
                        .createdAt(LocalDateTime.now())
                        .build()));
        if (order.getAmount() != amount) { // 코스 가격이 그새 바뀌었으면 권위 금액 갱신
            order.setAmount(amount);
            order.setOrderName(orderName(e));
            order.setUpdatedAt(LocalDateTime.now());
        }
        return PaymentPrepareResponse.of(order, clientKey, customerKey(student));
    }

    /**
     * 결제 승인 — 소유·상태·금액 검증 후 토스 {@code /v1/payments/confirm} 호출. DONE 이면 주문을 DONE 으로,
     * 신청을 CONFIRMED 로 확정. 멱등 — 이미 DONE 인 주문은 그대로 성공 반환(confirm 재호출/새로고침 대비).
     */
    @Transactional
    public PaymentConfirmResponse confirm(Account student, String paymentKey, String orderId, int amount) {
        PaymentOrder order = orderRepo.findByOrderId(orderId).orElseThrow(ResourceNotFoundException::new);
        Enrollment e = order.getEnrollment();
        if (e == null || e.getStudent() == null || !e.getStudent().getId().equals(student.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 주문 — 존재 숨김
        }
        if (order.getStatus() == PaymentStatus.DONE) {
            return PaymentConfirmResponse.of(order); // 멱등 — 이미 승인됨
        }
        if (order.getStatus() != PaymentStatus.READY) {
            throw new BadRequestException(); // 취소/실패 주문은 승인 불가
        }
        if (order.getAmount() != amount) {
            throw new BadRequestException(); // 클라이언트 금액이 서버 권위 금액과 불일치
        }
        if (e.getStatus() != EnrollmentStatus.PAYMENT_PENDING) {
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
        e.setStatus(EnrollmentStatus.CONFIRMED); // 결제 완료 = 확정
        return PaymentConfirmResponse.of(order);
    }

    /* ─── helpers ─── */

    /** 내 신청이고 결제 대기 상태여야 결제 가능. 없음/남의 것 = 404, 결제대기 아님 = 400. */
    private Enrollment requirePayable(Account student, Long enrollmentId) {
        Enrollment e = enrollmentRepo.findById(enrollmentId).orElseThrow(ResourceNotFoundException::new);
        if (e.getStudent() == null || !e.getStudent().getId().equals(student.getId())) {
            throw new ResourceNotFoundException();
        }
        if (e.getStatus() != EnrollmentStatus.PAYMENT_PENDING) {
            throw new BadRequestException(); // 수락(결제 대기) 상태에서만 결제
        }
        return e;
    }

    /** 권위 금액(원) = 코스 라이브 수강료 + 입장료 스냅샷 + 장비 스냅샷. */
    private int authoritativeAmount(Enrollment e) {
        int tuition = e.getCourse() == null ? e.getTuitionSnapshot() : e.getCourse().getPrice();
        return tuition + e.getEntrySnapshot() + e.getEquipmentSnapshot();
    }

    private String orderName(Enrollment e) {
        String title = e.getCourse() == null ? "수강" : e.getCourse().getTitle();
        return title + " (" + e.getRoundIndex() + "회차)";
    }

    /** 토스 주문번호 — 6~64자 [A-Za-z0-9-_]. enrollment 식별 + UUID 로 유일성. */
    private String newOrderId(Long enrollmentId) {
        return "enr-" + enrollmentId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 위젯 customerKey — 계정 식별(내부 id, PII 아님). 위젯이 요구하는 안정 키. */
    private String customerKey(Account student) {
        return "cust-" + student.getId();
    }
}
