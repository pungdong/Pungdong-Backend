package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.branding.BrandingPost;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 댓글. 대댓글은 <b>1단까지만</b> — {@code parent} 는 최상위 댓글만 가리킬 수 있다.
 *
 * <p>DB 로는 "부모의 부모가 없어야 한다"를 표현할 수 없어 서비스가 강제한다. 막지 않으면 스레드가
 * 무한히 깊어져 들여쓰기가 화면을 벗어나고, 디자인도 1-depth 로만 그려져 있다.
 *
 * <p><b>댓글만 soft delete 다.</b> 게시물은 hard delete + 되돌릴 수 있는 숨김인데 댓글은 반대인 이유:
 * 대댓글이 달린 부모를 물리 삭제하면 자식이 FK 로 끊기거나 스레드 맥락이 사라진다. 그래서
 * {@code isDeleted} 로 남기고 본문만 "삭제된 댓글입니다" 로 대체해 렌더한다 — 흔한 스레드 관례이기도 하다.
 */
@Entity
@Table(name = "community_comment")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CommunityComment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private BrandingPost post;

    /** null 이면 최상위 댓글. 값이 있으면 대댓글이고, 그 부모는 반드시 최상위여야 한다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private CommunityComment parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 1000)
    private String body;

    /** 삭제 표식. 행은 남기고 본문만 가린다 — 위 Javadoc 참고. */
    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 최상위 댓글인가 — 대댓글의 부모가 될 자격. */
    public boolean isTopLevel() {
        return parent == null;
    }
}
