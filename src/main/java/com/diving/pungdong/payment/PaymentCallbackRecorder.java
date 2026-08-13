package com.diving.pungdong.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 이니시스 콜백 수신을 {@link PaymentCallbackLog} 에 남긴다 — <b>별도 트랜잭션</b>({@code REQUIRES_NEW}).
 *
 * <p>콜백 컨트롤러는 트랜잭션 밖이고 승인은 자체 트랜잭션을 연다. 수신 기록을 승인 트랜잭션에 묶으면 승인이
 * 롤백될 때 <b>"콜백은 왔는데 기록이 없는"</b> 상태가 되므로, 승인 성패와 무관하게 즉시 커밋되도록 분리한다.
 * 기록 실패가 콜백 처리를 막지 않도록 예외는 삼키고 로그만 남긴다(기록은 보조 관측이지 결제 경로가 아니다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCallbackRecorder {

    private final PaymentCallbackLogJpaRepo repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String orderId, Map<String, String> form, CallbackOutcome outcome) {
        try {
            repo.save(PaymentCallbackLog.builder()
                    .orderId(orderId)
                    .pStatus(form.get("P_STATUS"))
                    .authTid(form.get("P_AUTH_TID"))
                    .tid(form.get("P_TID"))
                    .idcName(form.get("P_IDCNAME"))
                    .outcome(outcome)
                    .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build());
        } catch (RuntimeException e) {
            // 관측 실패가 콜백 처리(승인·리다이렉트)를 막으면 안 된다 — 로그만 남기고 넘어간다.
            log.error("[payment-inicis] 콜백 수신 기록 실패 orderId={} outcome={}", orderId, outcome, e);
        }
    }
}
