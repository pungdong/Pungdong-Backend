package com.diving.pungdong.notification;

import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.notification.NotificationType;
import com.diving.pungdong.notification.event.LectureNotificationEvent;
import com.diving.pungdong.notification.event.ReservationCancelledEvent;
import com.diving.pungdong.notification.event.ReservationCreatedEvent;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
        // at-least-once 전송이라 같은 알림이 중복 도달할 수 있다 → 앱 dedup 용 안정적 id 를 data 에 심는다.
        // outbox 행 1개 = notificationId 1개(재시도는 같은 payload 재전송이라 id 유지). 공유 data 맵을
        // 변형하지 않도록 복사본에 넣는다. 정책 = docs/features/push.md.
        Map<String, String> data = new LinkedHashMap<>(payload.getData() == null ? Map.of() : payload.getData());
        data.put("notificationId", UUID.randomUUID().toString());
        payload.setData(data);
        // 광고성(마케팅)은 야간(21~08 KST)이면 다음 08:00 으로 미뤄 큐잉(정보통신망법). 거래성은 즉시.
        LocalDateTime nextAttemptAt = type.getCategory().isMarketing()
                ? MarketingSendWindow.clamp(Instant.now())
                : now;
        outboxRepo.save(NotificationOutbox.builder()
                .type(type)
                .recipientAccountId(recipientId)
                .payload(serialize(payload))
                .status(NotificationStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(nextAttemptAt)
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
