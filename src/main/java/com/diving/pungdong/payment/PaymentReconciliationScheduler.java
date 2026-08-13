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
        try {
            // 전송 성공 직후의 정상 시도를 오탐하지 않도록 15분 넘게 미확정인 것만 본다.
            reconciliation.reportStuck(OffsetDateTime.now(ZoneOffset.UTC), 15);
        } catch (RuntimeException e) {
            log.error("[reconciliation] 대사 스윕 실패", e);
        }
    }
}
