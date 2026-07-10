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
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "notification_outbox", indexes = {
        @Index(name = "idx_outbox_status_next_attempt", columnList = "status,nextAttemptAt")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationOutbox {

    static final int MAX_ATTEMPTS = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Column(nullable = false)
    private Long recipientAccountId;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime sentAt;

    @Column(length = 1024)
    private String lastError;

    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.lastError = null;
    }

    public void markFailedAndScheduleRetry(String error, OffsetDateTime nextAttempt) {
        this.attempts += 1;
        this.lastError = truncate(error);
        if (this.attempts >= MAX_ATTEMPTS) {
            this.status = NotificationStatus.GAVE_UP;
        } else {
            this.status = NotificationStatus.FAILED;
            this.nextAttemptAt = nextAttempt;
        }
    }

    public void markGaveUp(String reason) {
        this.attempts += 1;
        this.status = NotificationStatus.GAVE_UP;
        this.lastError = truncate(reason);
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1024 ? s.substring(0, 1024) : s;
    }
}
