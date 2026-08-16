package com.diving.pungdong.chat;

import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 메시지 1건. <b>{@link #id} 가 곧 커서다</b>(단조증가 IDENTITY).
 *
 * <p>커서 페이지네이션을 쓰는 이유: 채팅은 append-heavy 라 새 메시지가 들어오면 offset 페이지가 밀려
 * 과거 스크롤에서 중복·누락이 난다. 이 레포의 다른 목록은 전부 {@code Pageable} + {@code PagedModel} 이라
 * <b>의도적 이탈</b>이다(패키지 CLAUDE.md 참고).
 *
 * <p>{@link #clientMessageId} 는 <b>전송 멱등키</b>다. 응답이 유실된 뒤 사용자가 재전송하면 중복이 남는데
 * FE 의 "자동 재시도 금지" 로는 <b>수동 재전송</b>을 못 막는다. UNIQUE {@code (sender_account_id,
 * client_message_id)} 로 DB 가 막고, 중복이면 에러가 아니라 기존 메시지를 200 으로 돌려준다.
 * UUID 포맷을 강제하지 않는다 — RN(Hermes)에 WebCrypto 가 없어 UUID 생성에 네이티브 의존성이 붙는다.
 */
@Entity
@Table(name = "chat_message",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_message_client",
                columnNames = {"sender_account_id", "client_message_id"}),
        indexes = {
                @Index(name = "ix_chat_message_room_id", columnList = "room_id,id"),
                @Index(name = "ix_chat_message_sender_created",
                        columnList = "sender_account_id,created_at")
        })
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ChatMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CASCADE 삭제는 V28 의 raw FK 가 한다 — 스칼라 컬럼이라 @OnDelete 는 아무 효과가 없어 달지 않는다. */
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    /** SYSTEM 이면 null. FK 없음(account 강결합 회피). */
    @Column(name = "sender_account_id")
    private Long senderAccountId;

    /** 전송 멱등키. SYSTEM 이면 null — MySQL 이 NULL 중복을 허용해 UNIQUE 와 공존한다. */
    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ChatMessageKind kind;

    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    /** 삭제 표식. v1 은 삭제 API 가 없어 항상 false — 모더레이션(백로그) 대비 자리만 둔다. */
    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
