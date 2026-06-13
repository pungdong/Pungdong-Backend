package com.diving.pungdong.venue.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * reconcile 잡 liveness heartbeat (venue.md "🔴 타협 불가"). reconcile 가 공식 위치 캐시 정합성의
 * 바닥이라, 잡이 조용히 죽으면 stale 가 무한정 간다 → 마지막 성공 시각이 임계({@code stale-threshold-ms},
 * 기본 30분 = 3주기)보다 오래면 {@code /actuator/health} 를 DOWN 으로 떨어뜨려 외부 모니터가 감지하게 한다.
 *
 * <p>{@code @Profile("!test")} — 스케줄러가 없는 테스트에서 health 를 흔들지 않는다. 실 페이징 연동은
 * Phase 4 ops(이 indicator 가 그 hook). 빈 이름 → health key {@code officialVenueReconcile}.
 */
@Slf4j
@Component
@Profile("!test")
public class OfficialVenueReconcileHealthIndicator implements HealthIndicator {

    private final OfficialVenueCache cache;
    private final long staleThresholdMs;

    public OfficialVenueReconcileHealthIndicator(
            OfficialVenueCache cache,
            @Value("${pungdong.venue.reconcile.stale-threshold-ms:1800000}") long staleThresholdMs) {
        this.cache = cache;
        this.staleThresholdMs = staleThresholdMs;
    }

    @Override
    public Health health() {
        Long last = cache.lastReconciledAt();
        if (last == null) {
            log.warn("[venue-sync] heartbeat DOWN — reconcile has never succeeded");
            return Health.down().withDetail("reason", "never reconciled").build();
        }
        long ageMs = System.currentTimeMillis() - last;
        if (ageMs > staleThresholdMs) {
            log.warn("[venue-sync] heartbeat DOWN — last reconcile {}ms ago (> {}ms)", ageMs, staleThresholdMs);
            return Health.down()
                    .withDetail("lastReconciledAt", last)
                    .withDetail("ageMs", ageMs)
                    .withDetail("staleThresholdMs", staleThresholdMs)
                    .build();
        }
        return Health.up().withDetail("lastReconciledAt", last).withDetail("ageMs", ageMs).build();
    }
}
