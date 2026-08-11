package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 댓글 좋아요 — 마커 행. {@link CommunityPostLike} 와 같은 이유로 {@code (comment, account)} UNIQUE 로
 * 멱등을 얻는다.
 */
@Entity
@Table(name = "community_comment_like",
        uniqueConstraints = @UniqueConstraint(name = "uk_community_comment_like",
                columnNames = {"comment_id", "account_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CommunityCommentLike {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    private CommunityComment comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
