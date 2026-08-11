package com.diving.pungdong.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 댓글 조회.
 *
 * <p><b>정렬은 서버가 고정한다 — {@code createdAt ASC}.</b> 디자인의 "최신순 ▾" 은 다른 옵션이 어디에도
 * 정의돼 있지 않아 정적 라벨로 처리하기로 했고, 스레드는 위에서 아래로 대화가 흐르는 오름차순이
 * 자연스럽다(대댓글이 부모 아래 시간순으로 붙는다).
 */
public interface CommunityCommentJpaRepo extends JpaRepository<CommunityComment, Long> {

    /**
     * 한 게시물의 <b>모든</b> 댓글을 시간순으로. 최상위/대댓글을 나눠 가져오지 않고 한 번에 읽어
     * 메모리에서 트리로 조립한다 — 두 번 조회하면 그 사이에 달린 댓글이 유실될 수 있고,
     * 1-depth 라 크기가 작아 한 번이 낫다.
     *
     * <p>삭제된 댓글도 포함한다 — 자식이 달린 부모는 "삭제된 댓글입니다" 로 자리를 지켜야 스레드가
     * 끊기지 않는다. 본문 가리기는 응답 조립 시점에 한다.
     */
    @Query("select c from CommunityComment c where c.post.id = :postId "
            + "order by c.createdAt asc, c.id asc")
    List<CommunityComment> findThread(@Param("postId") Long postId);

    Optional<CommunityComment> findByIdAndAccountId(Long id, Long accountId);

    /** 게시물 카드의 댓글 수. 삭제된 댓글은 세지 않는다 — 화면에 "댓글 3" 인데 2개만 보이면 안 된다. */
    @Query("select c.post.id, count(c) from CommunityComment c "
            + "where c.post.id in :postIds and c.isDeleted = false group by c.post.id")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);

    long countByPostIdAndIsDeletedFalse(Long postId);

    /** 대댓글이 하나라도 있나 — 삭제를 soft 로 할지 hard 로 할지 가른다. */
    boolean existsByParentId(Long parentId);
}
