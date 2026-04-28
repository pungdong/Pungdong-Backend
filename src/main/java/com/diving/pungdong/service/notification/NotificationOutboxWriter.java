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
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class NotificationOutboxWriter {

    private final NotificationOutboxJpaRepo outboxRepo;
    private final ObjectMapper objectMapper;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReservationCreated(ReservationCreatedEvent event) {
        NotificationPayload payload = NotificationPayload.builder()
                .title("예약 알림")
                .body(String.format("%s님이 %s 강의를 예약했습니다",
                        event.getStudentNickname(), event.getLectureTitle()))
                .data(commonReservationData(event.getLectureId(), event.getScheduleId(),
                        NotificationType.RESERVATION_CREATED))
                .build();
        enqueue(NotificationType.RESERVATION_CREATED, event.getInstructorAccountId(), payload);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReservationCancelled(ReservationCancelledEvent event) {
        NotificationPayload payload = NotificationPayload.builder()
                .title("예약 취소 알림")
                .body(String.format("%s님이 %s 강의 예약을 취소했습니다",
                        event.getStudentNickname(), event.getLectureTitle()))
                .data(commonReservationData(event.getLectureId(), event.getScheduleId(),
                        NotificationType.RESERVATION_CANCELLED))
                .build();
        enqueue(NotificationType.RESERVATION_CANCELLED, event.getInstructorAccountId(), payload);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onLectureNotification(LectureNotificationEvent event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", NotificationType.LECTURE_NOTIFICATION.name());
        data.put("lectureId", String.valueOf(event.getLectureId()));

        for (Long recipientId : event.getRecipientAccountIds()) {
            NotificationPayload payload = NotificationPayload.builder()
                    .title(event.getTitle())
                    .body(event.getBody())
                    .data(data)
                    .build();
            enqueue(NotificationType.LECTURE_NOTIFICATION, recipientId, payload);
        }
    }

    private Map<String, String> commonReservationData(Long lectureId, Long scheduleId, NotificationType type) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", type.name());
        data.put("lectureId", String.valueOf(lectureId));
        data.put("scheduleId", String.valueOf(scheduleId));
        return data;
    }

    private void enqueue(NotificationType type, Long recipientId, NotificationPayload payload) {
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

    private String serialize(NotificationPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification payload", e);
        }
    }
}
