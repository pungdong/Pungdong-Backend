package com.diving.pungdong.notification;

import com.diving.pungdong.account.FirebaseToken;
import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.account.FirebaseTokenJpaRepo;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
import com.diving.pungdong.notification.fcm.FcmGateway;
import com.diving.pungdong.notification.fcm.FcmGateway.SendResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryWorker {

    private final NotificationOutboxJpaRepo outboxRepo;
    private final FirebaseTokenJpaRepo firebaseTokenRepo;
    private final FcmGateway fcmGateway;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(Long id) {
        NotificationOutbox row = outboxRepo.findById(id).orElse(null);
        if (row == null
                || row.getStatus() == NotificationStatus.SENT
                || row.getStatus() == NotificationStatus.GAVE_UP) {
            return;
        }

        List<FirebaseToken> tokens = firebaseTokenRepo.findByAccount_Id(row.getRecipientAccountId());
        if (tokens.isEmpty()) {
            row.markGaveUp("recipient has no registered firebase tokens");
            log.warn("Notification {} gave up: recipient {} has no tokens", row.getId(), row.getRecipientAccountId());
            return;
        }

        NotificationPayload payload = deserialize(row.getPayload());
        NotificationCategory category = row.getType().getCategory();

        boolean anySuccess = false;
        boolean anyTransient = false;
        List<String> tokensToDelete = new ArrayList<>();

        for (FirebaseToken token : tokens) {
            SendResult result = fcmGateway.send(token.getToken(),
                    payload.getTitle(), payload.getBody(), payload.getData(), category);
            switch (result) {
                case SUCCESS:
                    anySuccess = true;
                    break;
                case PERMANENT_FAILURE:
                    tokensToDelete.add(token.getToken());
                    break;
                case TRANSIENT_FAILURE:
                    anyTransient = true;
                    break;
            }
        }

        for (String dead : tokensToDelete) {
            firebaseTokenRepo.deleteByToken(dead);
        }

        if (anySuccess) {
            row.markSent();
        } else if (anyTransient) {
            row.markFailedAndScheduleRetry("transient FCM failure on all tokens", nextRetryAt(row));
        } else {
            row.markGaveUp("all tokens permanent failure");
            log.warn("Notification {} gave up: all {} tokens returned permanent failure",
                    row.getId(), tokens.size());
        }
    }

    /**
     * {@link #deliver(Long)} 가 예외로 죽었을 때 <b>디스패처가</b> 호출하는 실패 기록 경로.
     *
     * <p>deliver 는 {@code REQUIRES_NEW} 라 예외 시 자기 트랜잭션이 통째로 롤백된다 — 즉 상태도
     * {@code attempts} 도 그대로 남는다. 그대로 두면 픽업 쿼리가 {@code ORDER BY createdAt ASC} 라
     * <b>다음 틱에도 같은 행이 선두로 재선택</b>되어 큐 전체가 영구 정지하고({@code poison pill}),
     * {@code attempts} 가 안 올라 {@code MAX_ATTEMPTS} 초과 → {@code GAVE_UP} 구제도 영영 발동하지 않는다.
     * 그래서 <b>새 트랜잭션</b>으로 실패를 기록해 백오프를 태운다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeliveryFailure(Long id, String error) {
        NotificationOutbox row = outboxRepo.findById(id).orElse(null);
        if (row == null
                || row.getStatus() == NotificationStatus.SENT
                || row.getStatus() == NotificationStatus.GAVE_UP) {
            return; // 그새 처리됨 — 멱등
        }
        row.markFailedAndScheduleRetry(error, nextRetryAt(row));
    }

    /** 다음 재시도 시각. 마케팅은 야간(21~08 KST) 밖으로 클램프한다(정보통신망법 §50). */
    private OffsetDateTime nextRetryAt(NotificationOutbox row) {
        Duration delay = backoff(row.getAttempts() + 1);
        return row.getType().getCategory().isMarketing()
                ? MarketingSendWindow.clamp(Instant.now().plus(delay))
                : OffsetDateTime.now(ZoneOffset.UTC).plus(delay);
    }

    private Duration backoff(int attempts) {
        // 30s -> 1m -> 2m -> 4m -> 8m -> ... capped at 1h
        long seconds = Math.min(30L * (1L << Math.min(attempts - 1, 6)), 3600L);
        return Duration.ofSeconds(seconds);
    }

    private NotificationPayload deserialize(String json) {
        try {
            return objectMapper.readValue(json, NotificationPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize notification payload", e);
        }
    }
}
