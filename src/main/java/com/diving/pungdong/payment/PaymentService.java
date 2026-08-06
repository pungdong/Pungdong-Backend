package com.diving.pungdong.payment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.payment.dto.PaymentConfirmResponse;
import com.diving.pungdong.payment.dto.PaymentPrepareResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * 결제 — 학생 측(준비/승인). enrollment "수락 → 결제 → 확정" 의 결제 단계. 실제 PG 호출은 {@link PaymentGateway}
 * 뒤에 있다(토스/이니시스/stub 교체). 다회차: 결제 단위는 <b>회차(EnrollmentRound)</b> — API 의 {@code enrollmentId} 는 회차 id 다.
 *
 * <p><b>보안 핵심</b>: 금액은 클라이언트를 신뢰하지 않는다. {@link #prepare}가 서버에서 권위 금액을 재계산해
 * {@link PaymentOrder}에 박고, {@link #confirm}은 클라이언트가 보낸 amount 가 그 값과 같을 때만 승인을
 * 호출한다 — 그것도 <b>주문에 박힌 금액</b>으로(PG 도 같은 금액 → 결제창 결제액과 다르면 거절). 시크릿은 BE 밖으로 안 나간다.
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
    private final PaymentGatewayRegistry gateways;
    private final OrderNoFormatter orderNoFormatter;

    public PaymentService(PaymentOrderJpaRepo orderRepo, EnrollmentRoundJpaRepo roundRepo,
                          PaymentGatewayRegistry gateways, OrderNoFormatter orderNoFormatter) {
        this.orderRepo = orderRepo;
        this.roundRepo = roundRepo;
        this.gateways = gateways;
        this.orderNoFormatter = orderNoFormatter;
    }

    /**
     * 결제 준비 — 수락된(PAYMENT_PENDING) 회차에 대해 권위 금액을 재계산하고 READY 주문을 만든다(멱등 —
     * 이미 READY 주문이 있으면 재사용, 금액 변동 시 갱신). FE 가 이 응답으로 위젯을 띄운다. {@code roundId} = 회차 id.
     */
    @Transactional
    public PaymentPrepareResponse prepare(Account student, Long roundId, boolean mobile, String client) {
        EnrollmentRound r = requirePayable(student, roundId);
        int amount = authoritativeAmount(r);

        PaymentOrder order = orderRepo.findByEnrollmentRoundIdAndStatus(roundId, PaymentStatus.READY)
                .orElseGet(() -> orderRepo.save(PaymentOrder.builder()
                        .orderId(newOrderId(roundId))
                        .enrollmentRound(r)
                        .amount(amount)
                        .orderName(orderName(r))
                        .status(PaymentStatus.READY)
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build()));
        if (order.getAmount() != amount) { // 스냅샷이 그새 갱신됐으면 권위 금액 갱신
            order.setAmount(amount);
            order.setOrderName(orderName(r));
            order.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        // 신규 결제는 전역 설정이 정한 PG 로. 결제창을 띄우는 이 시점의 PG 를 주문에 박제한다 —
        // 이후 승인·환불은 전역 설정이 바뀌어도 이 값으로 라우팅된다(PaymentGatewayRegistry 참고).
        PaymentGateway gateway = gateways.active();
        order.setProvider(gateway.provider()); // READY 주문 재사용 시에도 현재 PG 로 다시 박제(결제창을 새로 띄우므로)
        order.setClient(PaymentClient.from(client)); // 이니시스 콜백 리다이렉트 타겟(web/app) — 재진입 시에도 갱신

        // 결제창 구동값은 PG 어댑터가 만든다 — 이니시스는 P_ 파라미터+서명 계산(외부 호출 없음).
        var params = gateway.initParams(new PaymentGateway.InitCommand(
                order.getOrderId(), order.getOrderName(), order.getAmount(), customerKey(student), mobile));
        return PaymentPrepareResponse.of(order, orderNoFormatter.format(order.getId(), order.getCreatedAt()),
                gateway.provider(), params);
    }

    /**
     * 결제 승인 — 소유·상태·금액 검증 후 {@link PaymentGateway#confirm} 호출. 승인되면 주문을 DONE 으로,
     * 회차를 CONFIRMED 로 확정. 멱등 — 이미 DONE 인 주문은 그대로 성공 반환(confirm 재호출/새로고침 대비).
     */
    @Transactional
    public PaymentConfirmResponse confirm(Account student, String orderId, int amount, Map<String, String> pgPayload) {
        PaymentOrder order = orderRepo.findByOrderId(orderId).orElseThrow(ResourceNotFoundException::new);
        requireOwner(order, student); // 세션 기반 소유권(FE confirm — TOSS/STUB). 없음/남의 것 = 존재 숨김
        // FE 가 보낸 amount 대조 — READY 일 때만(이미 DONE 이면 멱등 우선, applyConfirm 이 처리).
        if (order.getStatus() == PaymentStatus.READY && order.getAmount() != amount) {
            throw new BadRequestException(); // 클라이언트 금액이 서버 권위 금액과 불일치
        }
        return applyConfirm(order, pgPayload);
    }

    /**
     * 이니시스 콜백 승인 — 결제창이 P_NEXT_URL(BE)로 POST 한 인증값으로 서버가 직접 승인한다. <b>세션이 없다</b>(콜백 POST 엔
     * 우리 JWT 가 없음) — 소유권 검증을 생략하는 대신 <b>P_AUTH_TID(우리 콜백에만 옴)가 인증</b>이다(위조 POST 는
     * 승인 호출에서 거절). 금액은 클라가 아니라 <b>주문 권위값</b>. FE confirm 과 동일한 {@link #applyConfirm} 을 탄다.
     */
    @Transactional
    public PaymentConfirmResponse confirmByCallback(String orderId, Map<String, String> pgPayload) {
        PaymentOrder order = orderRepo.findByOrderId(orderId).orElseThrow(ResourceNotFoundException::new);
        return applyConfirm(order, pgPayload);
    }

    /**
     * 승인 코어 — FE confirm 과 이니시스 콜백이 공유한다. 멱등(이미 DONE = 성공 반환)·상태검증·PG 승인·확정.
     * 금액은 언제나 <b>주문 권위값</b>({@code order.getAmount()})으로 PG 에 보낸다(FE 가 보낸 값 아님).
     */
    private PaymentConfirmResponse applyConfirm(PaymentOrder order, Map<String, String> pgPayload) {
        if (order.getStatus() == PaymentStatus.DONE) {
            return response(order); // 멱등 — 이미 승인됨(재호출/새로고침/콜백 재전송 대비)
        }
        if (order.getStatus() != PaymentStatus.READY) {
            throw new BadRequestException(); // 취소/실패 주문은 승인 불가
        }
        EnrollmentRound r = order.getEnrollmentRound();
        if (r == null || r.getStatus() != EnrollmentStatus.PAYMENT_PENDING) {
            throw new BadRequestException(); // 결제 대기 상태가 아님(이미 확정/취소 등)
        }

        // 승인은 <b>결제창을 띄운 그 PG</b> 로 간다 — pgPayload 가 그 PG 의 인증값이므로 전역 설정을 보면 안 된다.
        PaymentGateway.ConfirmResult result = gateways.forOrder(order.getProvider()).confirm(
                new PaymentGateway.ConfirmCommand(order.getOrderId(), order.getAmount(), pgPayload));
        if (!result.approved()) {
            throw new BadRequestException(); // PG 승인 미완(어댑터가 PG별 성공표현을 정규화)
        }

        order.setStatus(PaymentStatus.DONE);
        order.setPaymentKey(result.pgTransactionId()); // PG 거래 식별자(토스 paymentKey / 이니시스 P_TID) — 취소에 쓴다
        order.setMethod(result.method());
        order.setApprovedAt(result.approvedAt());
        order.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        r.setStatus(EnrollmentStatus.CONFIRMED); // 결제 완료 = 확정 (pay-first: 강사는 이후 수영장 예약)
        return response(order);
    }

    /**
     * 주문 상세 조회 — 성공화면(특히 이니시스: confirm 을 FE 가 안 해 리다이렉트 쿼리만 옴) + 새로고침/딥링크 재진입 복구용.
     * 소유권 검증(없음/남의 것 = 존재 숨김). 어느 PG 든 동일 모양({@link PaymentConfirmResponse}).
     */
    public PaymentConfirmResponse getOrder(Account student, String orderId) {
        PaymentOrder order = orderRepo.findByOrderId(orderId).orElseThrow(ResourceNotFoundException::new);
        requireOwner(order, student);
        return response(order);
    }

    /** 이니시스 콜백 리다이렉트용 — 주문의 client(web/app) + orderNo. 승인 성패와 무관하게 리다이렉트 타겟을 정한다. 없으면 null. */
    public OrderRedirect callbackRedirect(String orderId) {
        return orderRepo.findByOrderId(orderId)
                .map(o -> new OrderRedirect(o.getClient(), orderNoFormatter.format(o.getId(), o.getCreatedAt())))
                .orElse(null);
    }

    /** 콜백 리다이렉트 타겟 정보. client null(legacy)이면 컨트롤러가 web 으로 폴백. */
    public record OrderRedirect(PaymentClient client, String orderNo) {
    }

    /* ─── helpers ─── */

    private PaymentConfirmResponse response(PaymentOrder order) {
        return PaymentConfirmResponse.of(order, orderNoFormatter.format(order.getId(), order.getCreatedAt()));
    }

    /** 세션 기반 소유권 — 주문의 회차 주인이 나여야 한다. 없음/남의 것 = 존재 숨김(404 아닌 400, repo 컨벤션). */
    private void requireOwner(PaymentOrder order, Account student) {
        EnrollmentRound r = order.getEnrollmentRound();
        Account owner = r == null || r.getEnrollment() == null ? null : r.getEnrollment().getStudent();
        if (owner == null || !owner.getId().equals(student.getId())) {
            throw new ResourceNotFoundException();
        }
    }

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
