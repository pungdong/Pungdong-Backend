package com.diving.pungdong.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 좋아요 조회. 카운트는 <b>저장하지 않고 여기서 집계</b>한다 — 게시물에 역정규화 카운터를 두면
 * review 도메인이 이미 겪은 통계 갱신 버그를 그대로 물려받는다.
 */
public interface CommunityPostLikeJpaRepo extends JpaRepository<CommunityPostLike, Long> {

    Optional<CommunityPostLike> findByPostIdAndAccountId(Long postId, Long accountId);

    long countByPostId(Long postId);

    /**
     * 여러 게시물의 좋아요 수를 한 번에 — 피드 카드용. 게시물마다 count 를 부르면 페이지 크기만큼
     * 쿼리가 나간다(N+1). 반환은 {@code [postId, count]} 이고 0건인 글은 행이 없어 호출부가 0 으로 채운다.
     */
    @Query("select l.post.id, count(l) from CommunityPostLike l "
            + "where l.post.id in :postIds group by l.post.id")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);

    /** 내가 좋아요한 게시물 id 들 — 뷰어 상태({@code likedByMe})를 한 번에 판정한다. */
    @Query("select l.post.id from CommunityPostLike l "
            + "where l.account.id = :accountId and l.post.id in :postIds")
    List<Long> findLikedPostIds(@Param("accountId") Long accountId,
                                @Param("postIds") Collection<Long> postIds);
}
