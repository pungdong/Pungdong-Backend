package com.diving.pungdong.venue.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 공식 위치 reconcile 주기 트리거 — {@code @Profile("!test")} (테스트는 reconcile() 직접 호출).
 * 주기는 {@code pungdong.venue.reconcile.interval-ms}(기본 10분). 잡이 조용히 죽으면
 * {@link OfficialVenueReconcileHealthIndicator} 가 staleness 로 감지(heartbeat).
 *
 * <p>{@code @EnableScheduling} 은 {@code PungdongApplication} 에 이미 있음(notification 잡들과 공유).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class OfficialVenueReconcileScheduler {

    private final OfficialVenueReconciler reconciler;

    @Scheduled(
            initialDelayString = "${pungdong.venue.reconcile.interval-ms:600000}",
            fixedDelayString = "${pungdong.venue.reconcile.interval-ms:600000}")
    public void tick() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException e) {
            // 한 주기 실패는 다음 주기에 회복. 정합성 바닥은 full-set _rev 대조라 한 번 놓쳐도 따라잡는다.
            log.warn("[venue-sync] reconcile tick failed — will retry next interval", e);
        }
    }
}
