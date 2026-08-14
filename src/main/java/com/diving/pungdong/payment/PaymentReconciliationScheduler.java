package com.diving.pungdong.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 결제 대사 스위퍼 — {@link PaymentReconciliation#reportStuck} 를 주기 호출(기본 10분). 결과 미확인으로 오래
 * 남은 승인/환불 시도를 ERROR 로 올려 알림·대시보드가 잡게 한다. 운영 전용({@code @Profile("!test")} — 테스트는
 * 서비스를 직접 호출). 스케줄링은 앱 전역 {@code @EnableScheduling}.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

    private final PaymentReconciliation reconciliation;

    @Scheduled(fixedDelayString = "${pungdong.payment.reconciliation-sweep-ms:600000}")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 전송 성공 직후의 정상 시도/전이를 오탐하지 않도록 15분 넘게 정착된 것만 본다. 두 대사는 서로 독립이라
        // 각각 try/catch — 하나가 죽어도 나머지는 돈다.
        try {
            reconciliation.reportStuck(now, 15); // 결과 미확인 시도(승인 ATTEMPTED·환불 REQUESTED)
        } catch (RuntimeException e) {
            log.error("[reconciliation] 미확인 시도 대사 실패", e);
        }
        try {
            reconciliation.reportAmountMismatch(now, 15); // 순액 ≠ chargeTotal 드리프트(M1)
        } catch (RuntimeException e) {
            log.error("[reconciliation] 금액 정합 대사 실패", e);
        }
    }
}
