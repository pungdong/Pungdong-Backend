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
 * 게시물 좋아요 — 마커 행.
 *
 * <p><b>{@code (post, account)} UNIQUE 가 이 설계의 전부다.</b> 그 제약 덕에 좋아요 API 가 멱등해진다 —
 * POST 를 두 번 보내도 행은 하나라, 네트워크 재시도나 연타로 카운트가 부풀지 않는다. 클라이언트가
 * 낙관적 업데이트를 해도 응답으로 덮어쓰면 항상 수렴한다.
 *
 * <p>⚠️ 레거시 {@code lecture_mark}(강의 찜)에는 이 UNIQUE 가 <b>없어서</b> 같은 유저가 여러 번 찜할 수
 * 있다. 그 패턴을 베끼지 않는다. 올바른 선례는 {@code venue_favorite} 의 {@code (owner, venue_ref_id)}.
 *
 * <p>카운트는 이 테이블에서 <b>집계</b>한다 — 게시물에 역정규화 카운터를 두지 않는다. review 도메인이
 * 이미 통계 갱신 버그를 baseline 간극으로 달고 있어 같은 함정을 새로 만들지 않는다.
 */
@Entity
@Table(name = "community_post_like",
        uniqueConstraints = @UniqueConstraint(name = "uk_community_post_like",
                columnNames = {"post_id", "account_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CommunityPostLike {

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
