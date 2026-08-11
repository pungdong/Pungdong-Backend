package com.diving.pungdong.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 북마크 조회. 집계·뷰어상태 처리는 {@link CommunityPostLikeJpaRepo} 와 같은 이유로 일괄 조회다. */
public interface CommunityPostBookmarkJpaRepo extends JpaRepository<CommunityPostBookmark, Long> {

    Optional<CommunityPostBookmark> findByPostIdAndAccountId(Long postId, Long accountId);

    long countByPostId(Long postId);

    @Query("select b.post.id, count(b) from CommunityPostBookmark b "
            + "where b.post.id in :postIds group by b.post.id")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);

    @Query("select b.post.id from CommunityPostBookmark b "
            + "where b.account.id = :accountId and b.post.id in :postIds")
    List<Long> findBookmarkedPostIds(@Param("accountId") Long accountId,
                                     @Param("postIds") Collection<Long> postIds);
}
