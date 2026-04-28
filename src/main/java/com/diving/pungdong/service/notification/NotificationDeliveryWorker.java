package com.diving.pungdong.service.notification;

import com.diving.pungdong.domain.notification.NotificationOutbox;
import com.diving.pungdong.domain.notification.NotificationStatus;
import com.diving.pungdong.repo.notification.NotificationOutboxJpaRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryWorker {

    private final NotificationOutboxJpaRepo outboxRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(Long id) {
        NotificationOutbox row = outboxRepo.findById(id).orElse(null);
        if (row == null
                || row.getStatus() == NotificationStatus.SENT
                || row.getStatus() == NotificationStatus.GAVE_UP) {
            return;
        }

        // Phase 2-A: stub. Phase 2-B replaces this with a real FCM call and
        // categorises FCM errors (UNREGISTERED etc -> immediate GAVE_UP +
        // delete the dead token; transient errors -> markFailedAndScheduleRetry).
        log.info("[notification stub] type={} recipient={} payload={}",
                row.getType(), row.getRecipientAccountId(), row.getPayload());
        row.markSent();
    }
}
