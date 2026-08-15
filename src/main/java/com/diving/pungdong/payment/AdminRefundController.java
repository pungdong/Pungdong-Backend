package com.diving.pungdong.payment;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.payment.dto.ManualRefundRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 어드민 — 결제 주문 <b>수동 환불</b>. 정책 오산정·CS 보정으로 남은 잔액을 돌려줄 때 PG 콘솔이 아니라 여기로 —
 * 우리 원장({@code RefundOrder}·{@code payment_order.refunded_amount})에 기록이 남고 PG 라우팅·대사 가드가 그대로
 * 적용된다. 매처 {@code /admin/payments/**} → hasRole(ADMIN).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/payments")
public class AdminRefundController {

    private final RefundService refundService;

    /** 주문 잔액(또는 일부) 수동 환불. 응답 = 이번 취소액 + 주문 원금/누적환불/남은 잔액. */
    @PostMapping("/orders/{orderId}/refund")
    public ResponseEntity<RefundService.ManualRefundResult> refund(@PathVariable String orderId,
                                                                   @Valid @RequestBody ManualRefundRequest request,
                                                                   BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        return ResponseEntity.ok(refundService.refundOrderManually(orderId, request.getAmount(), request.getReason()));
    }
}
