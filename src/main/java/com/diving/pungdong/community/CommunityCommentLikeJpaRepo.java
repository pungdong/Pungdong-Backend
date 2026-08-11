package com.diving.pungdong.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 댓글 좋아요 조회. 스레드 한 번 그릴 때 댓글마다 세지 않도록 일괄 집계한다. */
public interface CommunityCommentLikeJpaRepo extends JpaRepository<CommunityCommentLike, Long> {

    Optional<CommunityCommentLike> findByCommentIdAndAccountId(Long commentId, Long accountId);

    long countByCommentId(Long commentId);

    @Query("select l.comment.id, count(l) from CommunityCommentLike l "
            + "where l.comment.id in :commentIds group by l.comment.id")
    List<Object[]> countByCommentIds(@Param("commentIds") Collection<Long> commentIds);

    @Query("select l.comment.id from CommunityCommentLike l "
            + "where l.account.id = :accountId and l.comment.id in :commentIds")
    List<Long> findLikedCommentIds(@Param("accountId") Long accountId,
                                   @Param("commentIds") Collection<Long> commentIds);

    void deleteByCommentId(Long commentId);
}
