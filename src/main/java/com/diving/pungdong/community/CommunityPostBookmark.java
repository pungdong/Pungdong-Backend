package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.branding.BrandingPost;
import lombok.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 게시물 북마크(저장) — 마커 행. 구조는 {@link CommunityPostLike} 와 같고 UNIQUE 로 멱등을 얻는다.
 *
 * <p>좋아요와 <b>별도 테이블</b>인 이유: 두 행동의 의미와 수명이 다르다. 좋아요는 남에게 보이는 반응이고
 * 북마크는 나만 보는 책갈피다. 한 테이블에 type 컬럼으로 합치면 "저장한 글" 목록 조회가 항상 type
 * 필터를 달고 다녀야 하고, 나중에 한쪽에만 필드가 붙을 때(예: 북마크 폴더) 서로를 오염시킨다.
 *
 * <p>{@code (account, created_at)} 인덱스가 있는 건 "저장한 글" 목록이 계정 기준 최신순으로 읽기
 * 때문이다 — 좋아요는 그 방향 조회가 없다.
 */
@Entity
@Table(name = "community_post_bookmark",
        uniqueConstraints = @UniqueConstraint(name = "uk_community_post_bookmark",
                columnNames = {"post_id", "account_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CommunityPostBookmark {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private BrandingPost post;

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
