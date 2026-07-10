package com.diving.pungdong.account;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 탈퇴 익명화 스위퍼 — 유예기간이 지난 탈퇴 계정의 PII 를 {@link AccountAnonymizationService} 로 파기.
 * 운영 전용({@code @Profile("!test")} — 테스트는 서비스를 직접 호출). 스케줄링은 앱 전역 {@code @EnableScheduling}.
 * 각 건 독립 트랜잭션 + 멱등이라 멀티태스크/재시도 안전.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AccountDeletionScheduler {

    private final AccountAnonymizationService anonymizationService;

    @Scheduled(cron = "${pungdong.account.deletion.sweep-cron:0 30 4 * * *}")
    public void sweep() {
        List<Long> dueIds = anonymizationService.findDueAccountIds(OffsetDateTime.now(ZoneOffset.UTC));
        if (dueIds.isEmpty()) {
            return;
        }
        int done = 0;
        for (Long id : dueIds) {
            try {
                anonymizationService.anonymize(id);
                done++;
            } catch (RuntimeException e) {
                log.warn("[anonymize] account {} 익명화 실패(다음 sweep 재시도)", id, e);
            }
        }
        log.info("[anonymize] {}/{} 건 익명화 완료", done, dueIds.size());
    }
}
