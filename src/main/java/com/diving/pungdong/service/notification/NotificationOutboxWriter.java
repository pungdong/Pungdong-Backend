package com.diving.pungdong.service.notification;

import com.diving.pungdong.domain.notification.NotificationOutbox;
import com.diving.pungdong.domain.notification.NotificationStatus;
import com.diving.pungdong.domain.notification.NotificationType;
import com.diving.pungdong.domain.notification.event.LectureNotificationEvent;
import com.diving.pungdong.domain.notification.event.ReservationCancelledEvent;
import com.diving.pungdong.domain.notification.event.ReservationCreatedEvent;
import com.diving.pungdong.repo.notification.NotificationOutboxJpaRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class NotificationOutboxWriter {

    private final NotificationOutboxJpaRepo outboxRepo;
    private final ObjectMapper objectMapper;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReservationCreated(ReservationCreatedEvent event) {
        enqueue(NotificationType.RESERVATION_CREATED, event.getInstructorAccountId(), event);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReservationCancelled(ReservationCancelledEvent event) {
        enqueue(NotificationType.RESERVATION_CANCELLED, event.getInstructorAccountId(), event);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onLectureNotification(LectureNotificationEvent event) {
        for (Long recipientId : event.getRecipientAccountIds()) {
            enqueue(NotificationType.LECTURE_NOTIFICATION, recipientId, event);
        }
    }

    private void enqueue(NotificationType type, Long recipientId, Object payload) {
        LocalDateTime now = LocalDateTime.now();
        outboxRepo.save(NotificationOutbox.builder()
                .type(type)
                .recipientAccountId(recipientId)
                .payload(serialize(payload))
                .status(NotificationStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification payload", e);
        }
    }
}
