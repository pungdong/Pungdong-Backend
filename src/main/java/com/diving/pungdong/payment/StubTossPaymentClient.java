package com.diving.pungdong.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 🔒 로컬 stub — 토스를 호출하지 않고 즉시 {@code DONE} 을 돌려준다. 로컬 개발이 외부 PG 에 묶이지 않게 하는
 * 기본 모드(외부 호출 0). 실 승인 검증은 {@code pungdong.payment.mode=toss} 로 전환 → {@link RealTossPaymentClient}.
 *
 * <p>(StubAddressApiClient / StubIdentityVerifier 와 동일 패턴.)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.payment.mode", havingValue = "stub", matchIfMissing = true)
public class StubTossPaymentClient implements TossPaymentClient {

    @Override
    public TossConfirmResult confirm(String paymentKey, String orderId, int amount) {
        log.info("[payment-stub] confirm orderId={} amount={} → DONE(고정)", orderId, amount);
        return new TossConfirmResult("DONE", "간편결제", OffsetDateTime.now(), null);
    }

    @Override
    public TossCancelResult cancel(String paymentKey, int cancelAmount, String reason) {
        log.info("[payment-stub] cancel paymentKey={} amount={} → CANCELED(고정)", paymentKey, cancelAmount);
        return new TossCancelResult("CANCELED", OffsetDateTime.now());
    }
}
