package com.diving.pungdong.chat;

import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 방별 읽음 지점. unread = 이 값보다 큰 id 중 <b>내가 보내지 않은</b> 메시지 수.
 *
 * <p>행이 없으면 {@code lastReadMessageId = 0} 으로 간주한다(집계는 LEFT JOIN + coalesce).
 */
@Entity
@Table(name = "chat_read_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_read_state",
                columnNames = {"room_id", "account_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ChatReadState {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "last_read_message_id", nullable = false)
    private long lastReadMessageId;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * 전진만 한다 — 폴링과 읽음 처리가 경합해도 되감기지 않는다. 이미 더 큰 값이면 no-op(멱등).
     *
     * @return 실제로 값이 바뀌었는지
     */
    public boolean advanceTo(long messageId) {
        if (messageId <= this.lastReadMessageId) {
            return false;
        }
        this.lastReadMessageId = messageId;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        return true;
    }

    @PrePersist
    void prePersist() {
        if (this.updatedAt == null) {
            this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
