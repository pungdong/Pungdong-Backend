package com.diving.pungdong.notification;

import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class NotificationDispatcher {

    private static final int BATCH_SIZE = 50;

    private final NotificationOutboxJpaRepo outboxRepo;
    private final NotificationDeliveryWorker deliveryWorker;

    // 폴링 주기 기본 3초(env 로 튜닝 가능). provisioned RDS = 인스턴스 시간당 과금이라 폴링 빈도↑ 비용 ~0,
    // 인덱스(idx_outbox_status_next_attempt) 탄 idle 쿼리는 sub-ms. 더 낮춰도 FCM 전달(~1~3s)이 지배해
    // 수확체감 + 폴링은 sub-second 도구 아님(그땐 event-driven). 즉시성 필요한 건 웹소켓(푸시 아님).
    @Scheduled(fixedDelayString = "${notification.dispatcher.fixed-delay-ms:3000}")
    public void dispatch() {
        List<NotificationOutbox> due = outboxRepo
                .findByStatusInAndNextAttemptAtBeforeOrderByCreatedAtAsc(
                        List.of(NotificationStatus.PENDING, NotificationStatus.FAILED),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        PageRequest.of(0, BATCH_SIZE));

        for (NotificationOutbox row : due) {
            deliveryWorker.deliver(row.getId());
        }
    }
}
