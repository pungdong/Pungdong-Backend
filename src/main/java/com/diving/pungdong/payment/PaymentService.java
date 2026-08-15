package com.diving.pungdong.payment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentService;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.enrollment.PaymentWindow;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ConcurrentRequestException;
import com.diving.pungdong.global.advice.exception.PaymentGatewayException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.enrollment.EnrollmentRefs;
import com.diving.pungdong.notification.event.PaymentCompletedEvent;
import com.diving.pungdong.global.advice.exception.VenueChangeRequiresReapplyException;
import com.diving.pungdong.payment.dto.PaymentConfirmResponse;
import com.diving.pungdong.payment.dto.PaymentPrepareResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 결제 — 학생 측(준비/승인). <b>선결제</b>(전 회차 동일, 2026-08-09 통일): 신청 직후(PENDING) 결제 → 승인 시
 * {@code ACCEPT_PENDING}(강사 결정 대기). 실제 PG 호출은 {@link PaymentGateway} 뒤에 있다(토스/이니시스/stub 교체).
 * 다회차: 결제 단위는 <b>회차(EnrollmentRound)</b> — API 의 {@code enrollmentId} 는 회차 id 다.
 *
 * <p><b>보안 핵심</b>: 금액은 클라이언트를 신뢰하지 않는다. {@link #prepare}가 서버에서 권위 금액을 재계산해
 * {@link PaymentOrder}에 박고, {@link #confirm}은 클라이언트가 보낸 amount 가 그 값과 같을 때만 승인을
 * 호출한다 — 그것도 <b>주문에 박힌 금액</b>으로(PG 도 같은 금액 → 결제창 결제액과 다르면 거절). 시크릿은 BE 밖으로 안 나간다.
 *
 * <p><b>권위 금액</b> = (첫 만남 회차면 수강료) + 부대비용(입장료+장비+추가세션비). 수강료는 enrollment 스냅샷
 * 고정(2026-06-28 — 환불 정산을 위해 라이브 재계산 폐기), 1회차에 전액, 2회차~ 는 부대비용만.
 */
// 명시적 빈 이름 — 원래는 레거시 com.diving.pungdong.service.PaymentService(죽은 예약 플로우)와 단순명이
// 같아 컴포넌트 스캔의 기본 빈 이름("paymentService")이 충돌했기 때문. 그 레거시 클래스는 v1 청산으로
// 제거됐으나(2026-08-15), 빈 이름을 지금 바꿀 이유가 없어 그대로 둔다(주입은 전부 타입 기반).
@Slf4j
@Service("enrollmentPaymentService")
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentOrderJpaRepo orderRepo;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final PaymentGatewayRegistry gateways;
    private final OrderNoFormatter orderNoFormatter;
    private final EnrollmentService enrollmentService; // 슬롯 변경 검증·좌석 hold·적용(payment→enrollment)
    private final SiteSettingsProvider siteSettings;   // 차액 결제창 window = paymentTtlHours
    private final PaymentApprovalLedger approvalLedger; // 승인 시도 원장(REQUIRES_NEW) — 청구 사실 durable 기록
    private final ApplicationEventPublisher events;     // 결제 완료 알림(notification outbox 가 수신)

    public PaymentService(PaymentOrderJpaRepo orderRepo, EnrollmentRoundJpaRepo roundRepo,
                          PaymentGatewayRegistry gateways, OrderNoFormatter orderNoFormatter,
                          EnrollmentService enrollmentService, SiteSettingsProvider siteSettings,
                          PaymentApprovalLedger approvalLedger, ApplicationEventPublisher events) {
        this.orderRepo = orderRepo;
        this.roundRepo = roundRepo;
        this.gateways = gateways;
        this.orderNoFormatter = orderNoFormatter;
        this.enrollmentService = enrollmentService;
        this.siteSettings = siteSettings;
        this.approvalLedger = approvalLedger;
        this.events = events;
    }

    /**
     * 차액 결제가 적용할 목표 슬롯 — 위치는 회차 고정, 일정(날짜·이용권·블록)만 바뀐다.
     *
     * @param venueRefId 클라이언트가 <b>사용자에게 보여준</b> 위치(선택). 값이 오면 회차의 현재 위치와 대조해
     *                   다르면 거부한다 — 위치는 이 경로로 못 바꾸므로, 안 막으면 학생이 고른 적 없는 원래
     *                   위치로 조용히 옮겨진다. null 이면 대조를 건너뛴다(하위호환).
     */
    public record SlotChangeTarget(LocalDate date, String ticketRef, LocalTime blockStart, LocalTime blockEnd,
                                   String venueRefId) {
    }

    /**
     * 결제 준비 — 결제 대기 회차(신청 직후 PENDING)에 대해 권위 금액을 재계산하고 READY 주문을 만든다(멱등 —
     * 이미 READY 주문이 있으면 재사용, 금액 변동 시 갱신). FE 가 이 응답으로 위젯을 띄운다. {@code roundId} = 회차 id.
     */
    @Transactional
    public PaymentPrepareResponse prepare(Account student, Long roundId, boolean mobile, String client) {
        return prepare(student, roundId, mobile, client, null);
    }

    /**
     * 결제 준비 — {@code target} 이 있으면 <b>슬롯 변경 차액 결제</b>다. 권위 금액 = (목표 슬롯 회차금액 − 현재 회차 순액)
     * 이고, 목표 슬롯을 주문에 실어 <b>승인되는 순간 슬롯이 교체 + 강사 재수락 대기</b>가 되게 한다. 결제창이 떠 있는 동안 그 자리는
     * 주문 귀속 hold 로 잡아둔다(안 잡으면 결제 중에 자리가 나가 돈만 받는 상태가 된다).
     */
    @Transactional
    public PaymentPrepareResponse prepare(Account student, Long roundId, boolean mobile, String client,
                                          SlotChangeTarget target) {
        EnrollmentRound r;
        int amount;
        int targetEntryFee = 0;
        if (target == null) {
            r = requirePayable(student, roundId);
            amount = authoritativeAmount(r);
        } else {
            // ★ 위치 가드 — 차액 결제는 위치를 못 바꾼다. 클라이언트가 다른 위치를 띄워놓고 보냈다면
            // (이용권·시간이 현재 위치에도 우연히 있으면) 학생이 고른 적 없는 곳으로 조용히 옮겨진다.
            // 값을 보내온 경우에만 대조 가능 — 1차 방어는 reschedule 이 -1019 를 먼저 내는 것.
            requireSameVenue(student, roundId, target.venueRefId());
            // 검증·가격 산정은 enrollment 소관(payment→enrollment, 허용 방향). 여기선 금액만 받아 주문을 만든다.
            EnrollmentService.SlotChangeQuote quote = enrollmentService.quoteSlotChange(
                    student, roundId, target.date(), target.ticketRef(), target.blockStart(), target.blockEnd());
            amount = quote.additionalAmount();
            targetEntryFee = quote.targetEntryFee();
            r = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        }
        final int authoritativeAmount = amount;
        final EnrollmentRound round = r;

        PaymentOrder order = orderRepo.findByEnrollmentRoundIdAndStatus(roundId, PaymentStatus.READY)
                .orElseGet(() -> createReadyOrder(round, authoritativeAmount));
        if (order.getAmount() != amount) { // 스냅샷이 그새 갱신됐으면 권위 금액 갱신
            order.setAmount(amount);
            order.setOrderName(orderName(r));
            order.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (target != null) {
            order.setTargetDate(target.date());
            order.setTargetTicketRef(target.ticketRef());
            order.setTargetBlockStart(target.blockStart());
            order.setTargetBlockEnd(target.blockEnd());
            // 결제창 window 동안 목표 슬롯 좌석 확보(주문 귀속 hold). 만석이면 여기서 400.
            enrollmentService.holdSlotForOrder(student, roundId, order.getId(),
                    target.date(), target.ticketRef(), target.blockStart(), target.blockEnd(),
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(siteSettings.current().paymentTtlHours()));
        }
        // 신규 결제는 전역 설정이 정한 PG 로. 결제창을 띄우는 이 시점의 PG 를 주문에 박제한다 —
        // 이후 승인·환불은 전역 설정이 바뀌어도 이 값으로 라우팅된다(PaymentGatewayRegistry 참고).
        PaymentGateway gateway = gateways.active();
        order.setProvider(gateway.provider()); // READY 주문 재사용 시에도 현재 PG 로 다시 박제(결제창을 새로 띄우므로)
        order.setClient(PaymentClient.from(client)); // 이니시스 콜백 리다이렉트 타겟(web/app) — 재진입 시에도 갱신

        // 결제창 구동값은 PG 어댑터가 만든다 — 이니시스는 P_ 파라미터+서명 계산(외부 호출 없음).
        var params = gateway.initParams(new PaymentGateway.InitCommand(
                order.getOrderId(), order.getOrderName(), order.getAmount(), customerKey(student), mobile));
        log.info("[payment] 결제 준비 order={} amount={} provider={} round={} client={}",
                order.getOrderId(), order.getAmount(), gateway.provider(), roundId, order.getClient());
        // 결제창 카운트다운 — 차액 결제는 주문의 window(좌석 hold 와 같은 기한), 일반 결제는 회차의
        // 미결제 window(신청 시각 기준). 시계가 서로 달라 분기한다.
        int ttlHours = siteSettings.current().paymentTtlHours();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Long expiresInSeconds = target != null
                ? PaymentWindow.remainingSeconds(PaymentWindow.deadline(order.getCreatedAt(), ttlHours), now)
                : PaymentWindow.remainingSecondsFor(r, ttlHours, now);
        return PaymentPrepareResponse.of(order, orderNoFormatter.format(order.getId(), order.getCreatedAt()),
                gateway.provider(), params, expiresInSeconds);
    }

    /**
     * READY 주문 생성 — 동시 prepare 방어. {@code uk_payment_order_ready_round}(회차당 READY 1개)가
     * 이중 주문을 DB 에서 막으므로, 진 쪽은 {@code DataIntegrityViolationException} 을 받는다. 같은 트랜잭션에선
     * 재조회가 안전하지 않아(rollback-only) {@link ConcurrentRequestException}(409/-1021)로 바꿔 재시도로 넘긴다 —
     * 재시도하면 먼저 만들어진 주문을 재사용한다. saveAndFlush 로 INSERT 를 즉시 내보내 위반을 여기서 잡는다.
     */
    private PaymentOrder createReadyOrder(EnrollmentRound round, int amount) {
        try {
            return orderRepo.saveAndFlush(PaymentOrder.builder()
                    .orderId(newOrderId(round.getId()))
                    .enrollmentRound(round)
                    .amount(amount)
                    .orderName(orderName(round))
                    .status(PaymentStatus.READY)
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ConcurrentRequestException(e);
        }
    }

    /**
     * 목표 위치 가드 — 클라이언트가 실어 보낸 위치가 회차의 현재 위치와 다르면 거부한다({@code -1019}).
     * 차액 결제 경로는 위치를 바꾸지 못하므로(승인 시 {@code applySlotChange} 가 {@code venueRefId} 를 그대로 둔다),
     * 다른 위치를 의도한 요청은 <b>성사시키면 안 되는</b> 요청이다. 값이 없으면(하위호환) 대조를 건너뛴다.
     */
    private void requireSameVenue(Account student, Long roundId, String targetVenueRefId) {
        if (targetVenueRefId == null) {
            return;
        }
        EnrollmentRound round = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        // 소유 검증은 곧이어 quoteSlotChange 가 하지만, 그 전에 남의 회차 위치를 떠보지 못하게 여기서도 막는다.
        if (round.getEnrollment() == null || round.getEnrollment().getStudent() == null
                || !round.getEnrollment().getStudent().getId().equals(student.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 회차 — 존재 숨김
        }
        if (!targetVenueRefId.equals(round.getVenueRefId())) {
            throw new VenueChangeRequiresReapplyException();
        }
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
        EnrollmentStatus before = r == null ? null : r.getStatus();
        if (order.isSlotChange()) {
            // 차액 결제는 결제완료·강사 결정 대기 회차의 일정 변경이다.
            if (before != EnrollmentStatus.ACCEPT_PENDING) {
                throw new BadRequestException(); // 그새 수락·취소·거절·만료된 회차
            }
        } else if (before != EnrollmentStatus.PENDING) {
            // 선결제(전 회차): 신청 직후(PENDING)에만 결제한다.
            throw new BadRequestException(); // 결제 가능 상태가 아님(이미 결제/확정/취소/만료 등)
        }

        // 이미 승인(청구)된 이력이 있으면 — 이전 확정이 롤백돼 주문이 READY 로 남은 것 — 재청구 없이 전진 확정.
        Optional<PaymentApproval> already = approvalLedger.findApproved(order.getId());
        if (already.isPresent()) {
            PaymentApproval a = already.get();
            log.warn("[payment] 이미 승인된 주문 재확정(이전 확정 롤백 추정) order={} tid={}", order.getOrderId(), a.getPgTransactionId());
            return finalizeApproval(order, r, a.getPgTransactionId(), a.getMethod(), a.getApprovedAt());
        }
        // 결과 미확인 승인 시도가 있으면 재청구 금지(이중청구 방지) — 사람이 PG 원장 대사 후 확정해야 다시 흐른다.
        if (approvalLedger.hasUnresolvedApproval(order.getId())) {
            throw new BadRequestException();
        }

        // 승인은 <b>결제창을 띄운 그 PG</b> 로 간다 — pgPayload 가 그 PG 의 인증값이므로 전역 설정을 보면 안 된다.
        // PG 호출 직전 원장에 ATTEMPTED 선기록(REQUIRES_NEW) → 청구 사실을 잃지 않는다(C1).
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Long attemptId = approvalLedger.recordAttempt(order.getId(), order.getAmount(), order.getProvider(), now);
        PaymentGateway.ConfirmResult result;
        try {
            result = gateways.forOrder(order.getProvider()).confirm(
                    new PaymentGateway.ConfirmCommand(order.getOrderId(), order.getAmount(), pgPayload));
        } catch (BadRequestException | PaymentGatewayException e) {
            approvalLedger.markFailed(attemptId, "REJECTED", e.getMessage(), now); // PG 명시적 거절 — 청구 안 됨
            throw e;
        } catch (RuntimeException e) {
            // 전송 실패(IllegalStateException 등) — 청구됐는지 모른다. ATTEMPTED 로 남겨 대사 대상(재승인 차단).
            log.error("[payment] 승인 전송 실패 — 결과 미확인 order={} attempt={}", order.getOrderId(), attemptId, e);
            throw e;
        }
        if (!result.approved()) {
            approvalLedger.markFailed(attemptId, "NOT_APPROVED", null, now);
            throw new BadRequestException(); // PG 승인 미완(어댑터가 PG별 성공표현을 정규화)
        }
        // 승인 성공 — 원장에 즉시 APPROVED 확정(REQUIRES_NEW). 이후 확정이 롤백돼도 청구 사실은 durable.
        approvalLedger.markApproved(attemptId, result.pgTransactionId(), result.method(), result.approvedAt(), now);
        return finalizeApproval(order, r, result.pgTransactionId(), result.method(), result.approvedAt());
    }

    /**
     * 승인 확정 — 청구가 (지금 또는 과거에) 성공했음을 전제로 주문을 {@code DONE} + 회차를 전이시킨다.
     * <b>outer 트랜잭션</b>에서 실행되므로 여기서 {@code @Version} 충돌·좌석 재검증 실패 등으로 예외가 나면
     * 이 확정은 롤백되지만, <b>승인 원장의 APPROVED 는 이미 커밋</b>돼 있어(재청구 없음) 재시도가 {@code findApproved}
     * 로 다시 이 지점에 도달해 전진 확정한다(정확히 한 번 청구 / 여러 번 적용). {@code DONE} 은 회차 전이까지 끝난 뒤
     * 세워지므로, 멱등 반환({@code status==DONE})은 "확정까지 완료"를 뜻한다.
     */
    private PaymentConfirmResponse finalizeApproval(PaymentOrder order, EnrollmentRound r,
                                                    String pgTransactionId, String method, OffsetDateTime approvedAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        order.setStatus(PaymentStatus.DONE);
        order.setPaymentKey(pgTransactionId); // PG 거래 식별자(토스 paymentKey / 이니시스 P_TID) — 취소에 쓴다
        order.setMethod(method);
        // 회계 불변식: DONE 인데 approvedAt=null 이면 안 된다(대사 불가). PG 가 승인시각을 주면 그걸(정확),
        // 안 주면 우리 승인처리 시각(now)으로 보정하고 warn — PG 원장과 크로스체크할 대상임을 표면화한다.
        if (approvedAt == null) {
            log.warn("[payment] PG 가 승인시각 미제공 order={} tid={} — 처리시각(now)으로 보정, PG 원장 대사 필요",
                    order.getOrderId(), pgTransactionId);
        }
        order.setApprovedAt(approvedAt != null ? approvedAt : now);
        order.setUpdatedAt(now);
        // 결제 완료 알림 — FE confirm 과 이니시스 콜백이 모두 이 확정부를 타므로 여기 한 곳이면 양쪽이 덮인다.
        // 차액 결제(아래 early return)도 실제로 돈이 나간 것이라 동일하게 알린다.
        //
        // 정확히 한 번만 나가는 근거 세 겹:
        //  (1) 순차 재호출 — applyConfirm 초입의 "이미 DONE 이면 그대로 반환" 멱등 가드에서 걸러진다.
        //  (2) 동시 재전송(콜백 중복 등) — 둘 다 가드를 통과할 수 있지만 PaymentOrder 의 @Version(#248)이
        //      blind overwrite 를 막아 진 쪽이 OptimisticLockException 으로 롤백된다.
        //  (3) 확정 롤백 후 재확정(#249 의 findApproved 경로) — 재청구 없이 이 지점에 다시 도달하는데,
        //      직전 시도의 발행은 그 트랜잭션과 함께 롤백돼 있으므로 커밋되는 발행은 여전히 한 번이다.
        // 셋 다 "알림 리스너가 MANDATORY 전파로 이 트랜잭션에 합류해 있다"는 사실에 기댄다 —
        // 확정이 롤백되면 알림도 같이 사라진다(청구는 원장에 남지만 알림은 안 나간다).
        publishPaymentCompleted(order, r);
        // 차액 결제 — 슬롯을 교체하고 강사 결정 대기로 되돌린다(학생이 고른 시간이라 강사 동의가 필요).
        if (order.isSlotChange()) {
            enrollmentService.applySlotChange(r.getId(), order.getId(),
                    order.getTargetDate(), order.getTargetTicketRef(),
                    order.getTargetBlockStart(), order.getTargetBlockEnd(), targetEntryFee(order, r));
            log.info("[payment] 슬롯 변경 차액 승인 확정 order={} round={} → {} {}~{}", order.getOrderId(), r.getId(),
                    order.getTargetDate(), order.getTargetBlockStart(), order.getTargetBlockEnd());
            return response(order);
        }
        // 선결제 → 강사 결정 대기(ACCEPT_PENDING). 결제시각을 respondedAt 에 = 강사 24h 무응답 시계 시작.
        r.setStatus(EnrollmentStatus.ACCEPT_PENDING);
        r.setRespondedAt(now);
        log.info("[payment] 승인 확정 order={} amount={} provider={} round={} tid={} method={} → {}",
                order.getOrderId(), order.getAmount(), order.getProvider(), r.getId(),
                pgTransactionId, method, EnrollmentStatus.ACCEPT_PENDING);
        return response(order);
    }

    /**
     * 결제 완료 알림 발행 — 돈이 계좌에서 나가는데 앱이 침묵하면 신뢰 문제가 된다.
     *
     * <p>수신자 좌표를 못 만들면 <b>발행을 건너뛴다</b>. 알림 리스너는 결제 트랜잭션에 합류하므로
     * 여기서 실패하면 <b>승인된 결제가 롤백</b>된다 — 알림 때문에 결제가 깨지면 안 된다.
     */
    private void publishPaymentCompleted(PaymentOrder order, EnrollmentRound round) {
        EnrollmentRefs refs = EnrollmentRefs.of(round);
        if (!refs.canNotifyStudent()) {
            return;
        }
        events.publishEvent(PaymentCompletedEvent.builder()
                .studentAccountId(refs.getStudentAccountId())
                .courseId(refs.getCourseId())
                .enrollmentId(refs.getEnrollmentId())
                .roundId(refs.getRoundId())
                .orderId(order.getId())
                .courseTitle(refs.courseTitleOrFallback())
                .amount(order.getAmount())
                .build());
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
        // 선결제(전 회차): 신청 직후(PENDING)에만 결제 가능.
        if (r.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 신청 직후(미결제)에서만 결제
        }
        return r;
    }

    /** 권위 금액(원) = (첫 만남이면 수강료 스냅샷) + 부대비용 스냅샷. 회차 단위. */
    /**
     * <b>차액 결제 미결제 만료</b> — 결제창 window({@code paymentTtlHours})가 지난 READY 차액 주문을 접고
     * 잡아둔 좌석을 반납한다. <b>예약은 손대지 않는다</b>(원래 슬롯 그대로) — 대기를 주문에만 뒀으므로 롤백할 게 없다.
     * 만료 건수 반환.
     */
    @Transactional
    public int sweepExpiredSlotChangeOrders(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusHours(siteSettings.current().paymentTtlHours());
        List<PaymentOrder> stale = orderRepo.findByStatusAndTargetDateIsNotNullAndCreatedAtBefore(
                PaymentStatus.READY, cutoff);
        for (PaymentOrder order : stale) {
            enrollmentService.releaseOrderHold(order.getId());
            order.setStatus(PaymentStatus.FAILED); // 결제창을 안 넘긴 주문 — 승인 없이 끝났다
            order.setUpdatedAt(now);
            log.info("[payment] 차액 결제 미결제 만료 order={} round={} (좌석 반납, 예약은 원래 슬롯 유지)",
                    order.getOrderId(), order.getEnrollmentRound() == null ? null : order.getEnrollmentRound().getId());
        }
        return stale.size();
    }

    /**
     * 목표 슬롯의 입장료 — 별도 컬럼 없이 유도한다. 차액 = (목표 회차금액 − 현재 회차금액) 인데 위치·장비가 그대로라
     * <b>갈리는 건 입장료뿐</b>이므로 {@code 차액 = 목표입장료 − 현재입장료} → {@code 목표입장료 = 현재입장료 + 차액}.
     */
    private int targetEntryFee(PaymentOrder order, EnrollmentRound r) {
        return r.getEntrySnapshot() + order.getAmount();
    }

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
