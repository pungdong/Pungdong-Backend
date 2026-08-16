package com.diving.pungdong.chat;

import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 방 참여자 — 강사 1명 + 결제완료({@code EnrollmentStatus.OCCUPYING}) 수강생 N명.
 *
 * <p><b>파생이 아니라 행으로 실체화한다.</b> 세션이 삭제되면 enrollment 로 참여자를 되짚을 수 없어
 * CLOSED 방의 권한 판정 자체가 불가능해지기 때문이다. 덤으로 푸시 fan-out 이 쿼리 1방이 되고 읽음상태
 * 앵커가 생긴다. enrollment 현재 상태와의 동기화는 방 조회·전송 때 {@code reconcile} 이 맡는다.
 *
 * <p><b>이탈은 행 삭제가 아니라 {@link #leftAt} 이다.</b> 이탈자가 과거에 남긴 메시지의 발신자 이름을
 * 계속 해석해야 하기 때문 — 지우면 그 말풍선의 이름이 빈칸이 된다. 대신 권한 판정과 참여자 목록은
 * {@code leftAt == null} 만 센다.
 */
@Entity
@Table(name = "chat_participant",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_participant",
                columnNames = {"room_id", "account_id"}),
        indexes = @Index(name = "ix_chat_participant_account", columnList = "account_id"))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ChatParticipant {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    /** FK 없음 — 채팅 도메인이 account 에 강결합되지 않게(user_notification 과 같은 기조). */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private ChatParticipantRole role;

    @Column(name = "joined_at")
    private OffsetDateTime joinedAt;

    /** null = 현재 참여자. 값이 있으면 이탈(환불·거절 등)했고 읽기/쓰기 권한이 없다. */
    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    @PrePersist
    void prePersist() {
        if (this.joinedAt == null) {
            this.joinedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public boolean isActive() {
        return leftAt == null;
    }

    /** 재합류(환불 후 재신청 등) — 같은 (room, account) 행을 되살린다. UNIQUE 라 새로 못 넣는다. */
    public void rejoin() {
        this.leftAt = null;
    }

    public void leave(OffsetDateTime now) {
        if (this.leftAt == null) {
            this.leftAt = now;
        }
    }
}
