package com.diving.pungdong.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 인앱 알림함 한 줄 — <b>도메인 사실 원장</b>.
 *
 * <p>{@link NotificationOutbox}(전송 시도 원장)와 목적이 다르다. outbox 는 "단말에 밀어넣기 성공했는가"
 * 를 기록하고 SENT 는 30일 뒤 삭제되며, 수신자에게 디바이스 토큰이 없으면 {@code GAVE_UP} 이 된다.
 * <b>웹 사용자·앱 미설치 사용자가 정확히 그 경우인데 그들이야말로 알림함이 가장 필요한 대상</b>이라,
 * outbox 를 알림함으로 겸용하면 durability 라는 도입 목적 자체가 무너진다. 그래서 별도 테이블이다.
 *
 * <p>두 테이블은 {@code notificationId}(UUID)로 1:1 상관된다 — 푸시 {@code data.notificationId} 와
 * 같은 값이라 "푸시 한 통 ↔ 알림함 한 줄" 이 추적된다. {@code NotificationOutboxWriter.enqueue} 가
 * <b>같은 트랜잭션</b>에서 둘을 함께 기록하므로, 비즈니스가 롤백되면 둘 다 사라진다(유령 알림 방지).
 */
@Entity
@Table(name = "user_notification",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_notification_notification_id", columnNames = "notificationId"),
        indexes = {
                @Index(name = "idx_user_notif_recipient_created",
                        columnList = "recipientAccountId,createdAt"),
                @Index(name = "idx_user_notif_recipient_unread",
                        columnList = "recipientAccountId,readAt")
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** outbox 행의 {@code data.notificationId} 와 동일. UNIQUE 라 재실행 멱등성의 근거이기도 하다. */
    @Column(nullable = false, length = 36, updatable = false)
    private String notificationId;

    /** FK 제약 없음 — outbox 와 같은 기조(알림 도메인이 account 에 강결합되지 않게). */
    @Column(nullable = false)
    private Long recipientAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    /**
     * 푸시 payload 의 data 와 <b>같은 맵</b>을 직렬화한 JSON({@code notificationId}·{@code type} 포함).
     * 앱이 {@code routeFromPush(row.data)} 를 재조립 없이 그대로 호출한다.
     */
    @Column(columnDefinition = "TEXT")
    private String data;

    /**
     * null = 미읽음. boolean 이 아닌 이유는 (1) 언제 읽었는지가 공짜로 남고,
     * (2) 원시 boolean 은 Lombok 게터가 {@code isRead()} 라 Jackson 이 프로퍼티를 둘로 늘리는
     * 직렬화 함정이 있기 때문(community/CLAUDE.md).
     */
    private OffsetDateTime readAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 멱등 — 이미 읽은 건 최초 시각을 유지한다(덮어쓰지 않음). */
    public void markRead() {
        if (this.readAt == null) {
            this.readAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
