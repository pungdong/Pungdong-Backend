package com.diving.pungdong.enrollment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

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
            int n = expiryService.sweepExpired(LocalDateTime.now());
            if (n > 0) {
                log.info("[expiry] {} 건 자동 만료(좌석 해제)", n);
            }
        } catch (RuntimeException e) {
            log.warn("[expiry] sweep 실패", e);
        }
    }
}
