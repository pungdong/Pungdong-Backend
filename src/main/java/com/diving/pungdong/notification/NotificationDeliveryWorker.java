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
import java.time.LocalDateTime;
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
            int nextAttempt = row.getAttempts() + 1;
            Duration delay = backoff(nextAttempt);
            // 마케팅 재시도가 야간으로 넘어가면 다음 08:00 KST 로 클램프(야간 광고 금지 유지).
            LocalDateTime retryAt = category.isMarketing()
                    ? MarketingSendWindow.clamp(java.time.Instant.now().plus(delay))
                    : LocalDateTime.now().plus(delay);
            row.markFailedAndScheduleRetry("transient FCM failure on all tokens", retryAt);
        } else {
            row.markGaveUp("all tokens permanent failure");
            log.warn("Notification {} gave up: all {} tokens returned permanent failure",
                    row.getId(), tokens.size());
        }
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
