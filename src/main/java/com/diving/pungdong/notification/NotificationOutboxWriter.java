package com.diving.pungdong.notification;

import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.notification.NotificationType;
import com.diving.pungdong.notification.event.CommunityCommentEvent;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NotificationOutboxWriter {

    /** 알림함 컬럼 길이(`user_notification.title` / `.body`)와 일치시킬 것. */
    private static final int TITLE_MAX = 255;
    private static final int BODY_MAX = 500;

    private final NotificationOutboxJpaRepo outboxRepo;
    private final UserNotificationJpaRepo userNotificationRepo;
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

    /**
     * 커뮤니티 댓글·답글 알림.
     *
     * <p>딥링크는 URL 을 만들어 보내지 않고 {@code data} 의 id 들로 클라이언트가 조립한다(기존 알림과 동일).
     * {@code commentId} 를 함께 싣는 이유는 글만 열면 어느 댓글 때문에 온 알림인지 알 수 없어서다.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCommunityComment(CommunityCommentEvent event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", NotificationType.COMMUNITY_COMMENT.name());
        data.put("postId", String.valueOf(event.getPostId()));
        data.put("commentId", String.valueOf(event.getCommentId()));

        // 제목이 없는 글(브랜딩에서 올라온 글)이 있어서 문구를 나눈다 — "null님의 글" 이 나가면 안 된다.
        String where = event.getPostTitle() == null || event.getPostTitle().isBlank()
                ? "회원님의 글"
                : String.format("'%s'", event.getPostTitle());
        String what = event.isReply() ? "답글" : "댓글";

        NotificationPayload payload = NotificationPayload.builder()
                .title(event.isReply() ? "새 답글" : "새 댓글")
                .body(String.format("%s님이 %s에 %s을 남겼어요", event.getActorNickName(), where, what))
                .data(data)
                .build();
        enqueue(NotificationType.COMMUNITY_COMMENT, event.getRecipientAccountId(), payload);
    }

    private Map<String, String> commonReservationData(Long lectureId, Long scheduleId, NotificationType type) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", type.name());
        data.put("lectureId", String.valueOf(lectureId));
        data.put("scheduleId", String.valueOf(scheduleId));
        return data;
    }

    private void enqueue(NotificationType type, Long recipientId, NotificationPayload payload) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // at-least-once 전송이라 같은 알림이 중복 도달할 수 있다 → 앱 dedup 용 안정적 id 를 data 에 심는다.
        // outbox 행 1개 = notificationId 1개(재시도는 같은 payload 재전송이라 id 유지). 공유 data 맵을
        // 변형하지 않도록 복사본에 넣는다. 정책 = docs/features/push.md.
        Map<String, String> data = new LinkedHashMap<>(payload.getData() == null ? Map.of() : payload.getData());
        String notificationId = UUID.randomUUID().toString();
        data.put("notificationId", notificationId);
        payload.setData(data);
        // 광고성(마케팅)은 야간(21~08 KST)이면 다음 08:00 으로 미뤄 큐잉(정보통신망법). 거래성은 즉시.
        OffsetDateTime nextAttemptAt = type.getCategory().isMarketing()
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

        // 알림함 행 — 같은 트랜잭션. 푸시가 실패해도(토큰 없음 → outbox GAVE_UP) 이 행은 남는다.
        // 여기 한 곳만 거치므로 모든 알림 타입이 자동으로 알림함에 적재된다(타입별 분기 불필요).
        // data 는 위 맵을 그대로 저장한다 — 앱이 routeFromPush(row.data) 를 재조립 없이 부른다.
        userNotificationRepo.save(UserNotification.builder()
                .notificationId(notificationId)
                .recipientAccountId(recipientId)
                .type(type)
                .title(truncate(payload.getTitle(), TITLE_MAX))
                .body(truncate(payload.getBody(), BODY_MAX))
                .data(serializeData(data))
                .readAt(null)
                .createdAt(now)
                .build());
    }

    /**
     * 알림함 컬럼 길이에 맞춰 자른다.
     *
     * <p>⚠️ 없으면 안 되는 이유: outbox payload 는 {@code @Lob} 이라 길이 제한이 없는데 알림함은
     * {@code varchar} 다. 강사가 자유 입력하는 {@code LECTURE_NOTIFICATION} 본문이 길면
     * {@code Data too long} 이 나고, <b>같은 트랜잭션이라 비즈니스 작업 전체가 롤백</b>된다
     * (수강신청이 알림 때문에 실패하는 최악의 결합).
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String serializeData(Map<String, String> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification data", e);
        }
    }

    private String serialize(NotificationPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification payload", e);
        }
    }
}
