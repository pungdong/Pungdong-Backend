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
 * 결제 — 학생 측. 토스 결제위젯 v2 흐름의 BE 엔드포인트.
 *
 * <p>매처: {@code /payments/**} → authenticated. 흐름:
 * <ol>
 *   <li>{@code POST /payments/prepare} — 수락된 신청의 주문 생성(서버 권위 금액·orderId·clientKey 반환).
 *       FE 가 이걸로 위젯을 띄운다.</li>
 *   <li>FE 위젯 결제 → 토스가 {@code successUrl?paymentKey&orderId&amount} 로 리다이렉트.</li>
 *   <li>{@code POST /payments/confirm} — 그 3개 값으로 승인(서버가 금액 대조 후 토스 승인 → 신청 CONFIRMED).</li>
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
        return ResponseEntity.ok(paymentService.prepare(account, request.getEnrollmentId()));
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
                account, request.getPaymentKey(), request.getOrderId(), request.getAmount()));
    }
}
