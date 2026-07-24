package com.diving.pungdong.payment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.payment.dto.PaymentConfirmRequest;
import com.diving.pungdong.payment.dto.PaymentConfirmResponse;
import com.diving.pungdong.payment.dto.PaymentPrepareRequest;
import com.diving.pungdong.payment.dto.PaymentPrepareResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 결제 — 학생 측. PG 중립 엔드포인트({@link com.diving.pungdong.payment.PaymentGateway} 뒤에 토스/KCP/stub).
 *
 * <p>매처: {@code /payments/**} → authenticated. 흐름:
 * <ol>
 *   <li>{@code POST /payments/prepare} — 수락된 신청의 주문 생성. 서버 권위 금액·orderId 와 함께
 *       {@code provider}(TOSS/KCP/STUB) + {@code params}(그 PG 의 결제창 구동값)를 반환한다.</li>
 *   <li>FE 가 {@code provider} 로 분기해 결제창 구동 → 결제창이 PG 고유 인증값을 돌려준다
 *       (토스 {@code paymentKey} / KCP {@code enc_data}·{@code enc_info}·{@code tran_cd}).</li>
 *   <li>{@code POST /payments/confirm} — 그 값들을 {@code pgPayload} 에 담아 승인
 *       (서버가 금액 대조 후 PG 승인 → 신청 CONFIRMED).</li>
 * </ol>
 * 상세 정책 docs/features/payment.md.
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** 결제 준비 — 수락된(PAYMENT_PENDING) 신청에 대한 주문 생성. 위젯 구동값 반환. */
    @PostMapping("/prepare")
    public ResponseEntity<PaymentPrepareResponse> prepare(@CurrentUser Account account,
                                                          @Valid @RequestBody PaymentPrepareRequest request,
                                                          BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok(paymentService.prepare(account, request.getEnrollmentId(), request.isMobile()));
    }

    /** 결제 승인 — 위젯 성공 리다이렉트의 (paymentKey, orderId, amount)로 토스 승인 → 신청 확정. */
    @PostMapping("/confirm")
    public ResponseEntity<PaymentConfirmResponse> confirm(@CurrentUser Account account,
                                                          @Valid @RequestBody PaymentConfirmRequest request,
                                                          BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok(paymentService.confirm(
                account, request.getOrderId(), request.getAmount(), request.getPgPayload()));
    }
}
