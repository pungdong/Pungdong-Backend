package com.diving.pungdong.block;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 유저 차단 — 계정 쌍(blocker → blocked)의 관계 1행.
 *
 * <p><b>차단은 상호 은닉이다.</b> 행은 방향을 갖지만(누가 눌렀는지는 남는다) <b>효과는 양방향</b>이다 —
 * 내가 A 를 차단하면 A 의 글·댓글이 내게서 사라지고, <b>내 글·댓글도 A 에게서 사라진다</b>.
 * 단방향으로 두면 "차단했는데 그 사람이 내 글에 계속 댓글을 단다" 는 상태가 남아 차단이 신고를 막지
 * 못한다. 대신 <b>차단당한 사실은 상대에게 알리지 않는다</b> — 조회는 그냥 "없는 것" 처럼 보인다.
 *
 * <p><b>{@code (blocker, blocked)} UNIQUE 가 멱등성의 근거다.</b> 같은 사람을 두 번 차단해도 1행이고,
 * 두 번째 요청은 에러가 아니라 200 이다(좋아요·북마크·신고와 같은 규칙 — 기대되는 결과는 4xx 가 아니다).
 *
 * <p><b>차단은 거래 관계를 끊지 않는다.</b> 내 강사를 차단해도 일정·결제·수강·단체 채팅은 그대로다.
 * 필터가 걸리는 표면은 커뮤니티(피드·댓글·프로필·추천 강사)뿐이다 — 정책은
 * {@code docs/features/moderation.md}.
 */
@Entity
@Table(name = "account_block",
        uniqueConstraints = @UniqueConstraint(name = "uk_account_block_once",
                columnNames = {"blocker_account_id", "blocked_account_id"}),
        indexes = @Index(name = "ix_account_block_reverse",
                columnList = "blocked_account_id, blocker_account_id"))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AccountBlock {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 차단을 누른 사람. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocker_account_id", nullable = false)
    private Account blocker;

    /** 차단당한 사람. 이 사람에게는 아무것도 알리지 않는다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_account_id", nullable = false)
    private Account blocked;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
