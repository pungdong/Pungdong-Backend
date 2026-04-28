package com.diving.pungdong.service.notification;

import com.diving.pungdong.domain.notification.NotificationOutbox;
import com.diving.pungdong.domain.notification.NotificationStatus;
import com.diving.pungdong.repo.notification.NotificationOutboxJpaRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class NotificationDispatcher {

    private static final int BATCH_SIZE = 50;

    private final NotificationOutboxJpaRepo outboxRepo;
    private final NotificationDeliveryWorker deliveryWorker;

    @Scheduled(fixedDelay = 10_000)
    public void dispatch() {
        List<NotificationOutbox> due = outboxRepo
                .findByStatusInAndNextAttemptAtBeforeOrderByCreatedAtAsc(
                        List.of(NotificationStatus.PENDING, NotificationStatus.FAILED),
                        LocalDateTime.now(),
                        PageRequest.of(0, BATCH_SIZE));

        for (NotificationOutbox row : due) {
            deliveryWorker.deliver(row.getId());
        }
    }
}
