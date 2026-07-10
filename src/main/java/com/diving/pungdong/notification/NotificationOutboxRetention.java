package com.diving.pungdong.notification;

import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class NotificationOutboxRetention {

    private final NotificationOutboxJpaRepo outboxRepo;

    @Value("${notification.outbox.sent-retention-days:30}")
    private int sentRetentionDays;

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgeOldSentRows() {
        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusDays(sentRetentionDays);
        int deleted = outboxRepo.deleteByStatusAndCreatedAtBefore(NotificationStatus.SENT, threshold);
        log.info("Purged {} SENT notifications older than {} ({}d retention). FAILED/GAVE_UP rows preserved.",
                deleted, threshold, sentRetentionDays);
    }
}
