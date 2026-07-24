package com.diving.pungdong.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🔒 로컬/테스트 stub — 외부 PG 를 호출하지 않고 즉시 승인을 돌려준다. 로컬 개발이 외부 PG 에 묶이지 않게 하는
 * 기본 모드(외부 호출 0). 실 승인 검증은 {@code pungdong.payment.mode=toss|kcp} 로 전환.
 *
 * <p>{@link #provider()} 가 {@link PaymentProvider#STUB} 이라 FE 는 결제창을 띄우지 않고 바로 confirm 을 부르면 된다.
 *
 * <p>(StubAddressApiClient / StubIdentityVerifier 와 동일 패턴.)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.payment.mode", havingValue = "stub", matchIfMissing = true)
public class StubPaymentGateway implements PaymentGateway {

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.STUB;
    }

    @Override
    public Map<String, String> initParams(InitCommand command) {
        log.info("[payment-stub] init orderId={} amount={} → 결제창 없음", command.orderId(), command.amount());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("customerKey", command.customerKey());
        return params;
    }

    @Override
    public ConfirmResult confirm(ConfirmCommand command) {
        log.info("[payment-stub] confirm orderId={} amount={} → 승인(고정)", command.orderId(), command.amount());
        return new ConfirmResult(true, "DONE", "간편결제", OffsetDateTime.now(), null,
                "stub-" + command.orderId()); // 취소 식별자도 stub 에서 자체 생성
    }

    @Override
    public CancelResult cancel(String pgTransactionId, int cancelAmount, String reason) {
        log.info("[payment-stub] cancel pgTransactionId={} amount={} → 취소(고정)", pgTransactionId, cancelAmount);
        return new CancelResult(true, "CANCELED", OffsetDateTime.now());
    }
}
