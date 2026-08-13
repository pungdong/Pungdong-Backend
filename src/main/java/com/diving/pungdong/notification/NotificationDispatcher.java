package com.diving.pungdong.notification;

import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
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
            Long id = row.getId();
            try {
                deliveryWorker.deliver(id);
            } catch (RuntimeException e) {
                // poison pill 방지. deliver 는 REQUIRES_NEW 라 예외 시 자기 트랜잭션이 롤백돼
                // 상태·attempts 가 그대로 남는데, 픽업이 ORDER BY createdAt ASC 라 그대로 두면
                // 다음 틱에도 같은 행이 선두로 뽑혀 큐 전체가 멈춘다(그 사이 뒤 행들은 영영 미발송).
                // 별도 트랜잭션으로 실패를 기록해 백오프를 태우고, 이 틱의 남은 행은 계속 처리한다.
                log.error("Notification {} 발송 중 예외 — 실패 기록 후 다음 행으로", id, e);
                try {
                    deliveryWorker.recordDeliveryFailure(id, e.toString());
                } catch (RuntimeException recordFailed) {
                    // 기록마저 실패(예: DB 장애). 이 행은 다음 틱에 다시 시도되지만,
                    // 배치를 멈추지는 않는다.
                    log.error("Notification {} 실패 기록도 실패 — 이번 틱 건너뜀", id, recordFailed);
                }
            }
        }
    }
}
