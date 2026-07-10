package com.diving.pungdong.enrollment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 좌석 만료 스위퍼 — {@link EnrollmentExpiryService#sweepExpired} 를 주기 호출(기본 5분). 운영 전용
 * ({@code @Profile("!test")} — 테스트는 서비스를 직접 호출). 스케줄링은 앱 전역 {@code @EnableScheduling}.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class EnrollmentExpiryScheduler {

    private final EnrollmentExpiryService expiryService;

    @Scheduled(fixedDelayString = "${pungdong.enrollment.expiry-sweep-ms:300000}")
    public void sweep() {
        try {
            int expired = expiryService.sweepExpired(OffsetDateTime.now(ZoneOffset.UTC));
            if (expired > 0) {
                log.info("[expiry] {} 건 자동 만료(좌석 해제)", expired);
            }
        } catch (RuntimeException e) {
            log.warn("[expiry] sweep 실패", e);
        }
        try {
            int lapsed = expiryService.sweepExpiredProposals(OffsetDateTime.now(ZoneOffset.UTC));
            if (lapsed > 0) {
                log.info("[proposal-expiry] {} 건 제안 만료(보장 hold 해제)", lapsed);
            }
        } catch (RuntimeException e) {
            log.warn("[proposal-expiry] sweep 실패", e);
        }
        try {
            int done = expiryService.sweepAutoDone(LocalDate.now());
            if (done > 0) {
                log.info("[auto-done] {} 건 자동 완료(세션일 경과)", done);
            }
        } catch (RuntimeException e) {
            log.warn("[auto-done] sweep 실패", e);
        }
    }
}
