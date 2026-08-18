package com.diving.pungdong.notification;

import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.notification.NotificationType;
import com.diving.pungdong.notification.event.ChatMessageEvent;
import com.diving.pungdong.notification.event.CommunityCommentEvent;
import com.diving.pungdong.notification.event.EnrollmentAcceptedEvent;
import com.diving.pungdong.notification.event.EnrollmentExpiredEvent;
import com.diving.pungdong.notification.event.EnrollmentRejectedEvent;
import com.diving.pungdong.notification.event.EnrollmentSlotsProposedEvent;
import com.diving.pungdong.notification.event.EnrollmentSubmittedEvent;
import com.diving.pungdong.notification.event.LectureNotificationEvent;
import com.diving.pungdong.notification.event.PaymentCompletedEvent;
import com.diving.pungdong.notification.event.RefundCompletedEvent;
import com.diving.pungdong.notification.event.ReservationCancelledEvent;
import com.diving.pungdong.notification.event.ReservationCreatedEvent;
import com.diving.pungdong.notification.event.RoundCompletedEvent;
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

        // 제목은 V31 부터 NOT NULL 이라 아래 폴백은 사실상 도달하지 않는다 — 그래도 남겨둔다:
        // 알림 문구에 "null님의 글" 이 나가는 사고는 비싸고, 가드는 한 줄이다.
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

    // ── 채팅(chat) 흐름 ────────────────────────────────────────────────────

    /**
     * 세션 단체 채팅 새 메시지 — 발신자를 뺀 참여자 전원에게 fan-out.
     *
     * <p>수신자별로 {@code enqueue} 를 한 번씩 부른다(= outbox 행 N개 + 알림함 행 N개). 발행자 트랜잭션에
     * 그대로 합류하므로 메시지 저장이 롤백되면 알림도 함께 사라진다. 수신자 목록은 발행자가 이미 만들어
     * 실어 보내므로 여기서 재조회하지 않는다({@link #onLectureNotification} 과 같은 형태).
     *
     * <p><b>딥링크가 방으로 직행한다</b> — {@code roomId} 하나면 앱이 채팅방을 연다. 다른 타입처럼
     * 허브로 보내면 채팅은 목록 메뉴가 없어 알림이 쓸모없어진다. {@code sessionId} 는 싣지 않는다:
     * 같은 것을 가리키는 이름이 둘이면 어느 걸로 이동할지가 호출부마다 갈린다.
     *
     * <p>제목은 방 스냅샷에서 만든다 — 세션이 지워진 뒤에 재시도로 발송돼도 "null회차" 가 나가지 않게.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onChatMessage(ChatMessageEvent event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", NotificationType.CHAT_MESSAGE.name());
        putIfPresent(data, "roomId", event.getRoomId());
        putIfPresent(data, "messageId", event.getMessageId());

        String title = chatTitle(event.getCourseTitle(), event.getRoundIndex());
        String body = String.format("%s: %s", event.getSenderNickName(), event.getPreview());

        for (Long recipientId : event.getRecipientAccountIds()) {
            NotificationPayload payload = NotificationPayload.builder()
                    .title(title)
                    .body(body)
                    .data(data)
                    .build();
            enqueue(NotificationType.CHAT_MESSAGE, recipientId, payload);
        }
    }

    /** "AIDA2 2회차" / 회차를 모르면 코스명만 / 코스명도 없으면 중립 문구. */
    private String chatTitle(String courseTitle, Integer roundIndex) {
        if (courseTitle == null || courseTitle.isBlank()) {
            return "회차 채팅";
        }
        return roundIndex == null ? courseTitle : String.format("%s %d회차", courseTitle, roundIndex);
    }

    // ── 수강(enrollment) 흐름 ──────────────────────────────────────────────

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEnrollmentAccepted(EnrollmentAcceptedEvent event) {
        enqueue(NotificationType.ENROLLMENT_ACCEPTED, event.getStudentAccountId(),
                NotificationPayload.builder()
                        .title("수강 확정")
                        .body(String.format("%s님이 %s 신청을 수락했어요",
                                event.getInstructorNickName(), event.getCourseTitle()))
                        .data(enrollmentData(NotificationType.ENROLLMENT_ACCEPTED,
                                event.getCourseId(), event.getEnrollmentId(), event.getRoundId()))
                        .build());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEnrollmentRejected(EnrollmentRejectedEvent event) {
        enqueue(NotificationType.ENROLLMENT_REJECTED, event.getStudentAccountId(),
                NotificationPayload.builder()
                        .title("수강 거절")
                        .body(String.format("%s님이 %s 신청을 거절했어요. 결제하신 금액은 전액 환불됩니다",
                                event.getInstructorNickName(), event.getCourseTitle()))
                        .data(enrollmentData(NotificationType.ENROLLMENT_REJECTED,
                                event.getCourseId(), event.getEnrollmentId(), event.getRoundId()))
                        .build());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEnrollmentSlotsProposed(EnrollmentSlotsProposedEvent event) {
        enqueue(NotificationType.ENROLLMENT_SLOTS_PROPOSED, event.getStudentAccountId(),
                NotificationPayload.builder()
                        .title("일정 제안 도착")
                        .body(String.format("%s님이 가능한 일정을 제안했어요. 확인하고 선택해 주세요",
                                event.getInstructorNickName()))
                        .data(enrollmentData(NotificationType.ENROLLMENT_SLOTS_PROPOSED,
                                event.getCourseId(), event.getEnrollmentId(), event.getRoundId()))
                        .build());
    }

    /** 만료는 두 갈래다 — 결제 여부에 따라 환불 안내가 붙고 안 붙고가 갈린다. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEnrollmentExpired(EnrollmentExpiredEvent event) {
        // paid 갈래에도 코스명을 넣는다 — 여러 강의를 신청한 유저는 강사 이름만으로 어느 건인지 못 가린다.
        // (승인 문구의 미세 수정. staging 실기기 검수 목록에 "문구 변경분" 으로 올림.)
        String body = event.isPaid()
                ? String.format("%s님이 24시간 내에 응답하지 않아 %s 신청이 취소되고 전액 환불되었어요",
                        event.getInstructorNickName(), event.getCourseTitle())
                : String.format("결제 기한이 지나 %s 신청이 취소되었어요", event.getCourseTitle());
        enqueue(NotificationType.ENROLLMENT_EXPIRED, event.getStudentAccountId(),
                NotificationPayload.builder()
                        .title("신청 만료")
                        .body(body)
                        .data(enrollmentData(NotificationType.ENROLLMENT_EXPIRED,
                                event.getCourseId(), event.getEnrollmentId(), event.getRoundId()))
                        .build());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEnrollmentSubmitted(EnrollmentSubmittedEvent event) {
        enqueue(NotificationType.ENROLLMENT_SUBMITTED, event.getInstructorAccountId(),
                NotificationPayload.builder()
                        .title("새 수강신청")
                        .body(String.format("%s님이 %s을 신청했어요",
                                event.getStudentNickName(), event.getCourseTitle()))
                        .data(enrollmentData(NotificationType.ENROLLMENT_SUBMITTED,
                                event.getCourseId(), event.getEnrollmentId(), event.getRoundId()))
                        .build());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRoundCompleted(RoundCompletedEvent event) {
        enqueue(NotificationType.ROUND_COMPLETED, event.getStudentAccountId(),
                NotificationPayload.builder()
                        .title("수강 완료")
                        .body(String.format("%s 수업이 완료되었어요. 어떠셨는지 후기를 남겨주세요",
                                event.getCourseTitle()))
                        .data(enrollmentData(NotificationType.ROUND_COMPLETED,
                                event.getCourseId(), event.getEnrollmentId(), event.getRoundId()))
                        .build());
    }

    // ── 결제(payment) 흐름 ────────────────────────────────────────────────

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        Map<String, String> data = enrollmentData(NotificationType.PAYMENT_COMPLETED,
                event.getCourseId(), event.getEnrollmentId(), event.getRoundId());
        putIfPresent(data, "orderId", event.getOrderId());
        enqueue(NotificationType.PAYMENT_COMPLETED, event.getStudentAccountId(),
                NotificationPayload.builder()
                        .title("결제 완료")
                        .body(String.format("%s %s원 결제가 완료되었어요",
                                event.getCourseTitle(), formatAmount(event.getAmount())))
                        .data(data)
                        .build());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRefundCompleted(RefundCompletedEvent event) {
        Map<String, String> data = enrollmentData(NotificationType.REFUND_COMPLETED,
                event.getCourseId(), event.getEnrollmentId(), event.getRoundId());
        putIfPresent(data, "orderId", event.getOrderId());
        enqueue(NotificationType.REFUND_COMPLETED, event.getStudentAccountId(),
                NotificationPayload.builder()
                        .title("환불 완료")
                        .body(String.format("%s %s원이 환불되었어요",
                                event.getCourseTitle(), formatAmount(event.getAmount())))
                        .data(data)
                        .build());
    }

    /**
     * enrollment 계열 공통 라우팅 좌표.
     *
     * <p>{@code courseId} 는 v1 라우팅에 쓰이지 않는다(앱은 파라미터 없는 허브로 착지한다) — 비용이 0 이고
     * 리스트 컨텍스트·향후 회차 상세 화면을 위해 미리 실어 둔다. 레거시 {@code lectureId}/{@code scheduleId}
     * 는 신규 타입에서 쓰지 않는다.
     */
    private Map<String, String> enrollmentData(NotificationType type, Long courseId,
                                               Long enrollmentId, Long roundId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", type.name());
        putIfPresent(data, "courseId", courseId);
        putIfPresent(data, "enrollmentId", enrollmentId);
        putIfPresent(data, "roundId", roundId);
        return data;
    }

    /** null id 를 {@code "null"} 문자열로 넣지 않는다 — 앱이 {@code Number("null")} 로 NaN 을 만든다. */
    private void putIfPresent(Map<String, String> data, String key, Long value) {
        if (value != null) {
            data.put(key, String.valueOf(value));
        }
    }

    /** 1234567 → "1,234,567". 금액은 사람이 읽는 문구에 들어가므로 천단위 구분이 필요하다. */
    private String formatAmount(int amount) {
        return String.format("%,d", amount);
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
