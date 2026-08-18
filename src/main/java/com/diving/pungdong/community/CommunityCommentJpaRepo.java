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

    /**
     * 차단 관계인 작성자의 댓글(과 그 답글)을 뺀 술어. 스레드가 <b>보여주는 것과 같은 기준</b>이어야
     * 한다 — 수만 전체를 세면 "댓글 3인데 2개 보임" 이 된다(삭제 댓글을 세지 않는 것과 같은 이유).
     *
     * <p><b>절이 둘인 이유</b>: 차단한 사람의 최상위 댓글이 사라지면 그 아래 달린 남의 답글도 함께
     * 사라진다(부모가 없으면 스레드에 붙을 자리가 없다). 그래서 작성자 조건과 <b>부모의 작성자</b>
     * 조건을 모두 본다.
     */
    String NOT_BLOCKED = "and not exists (select 1 from AccountBlock ab "
            + "where (ab.blocker.id = :viewerId and ab.blocked = c.account) "
            + "or (ab.blocked.id = :viewerId and ab.blocker = c.account)) "
            + "and (c.parent is null or not exists (select 1 from AccountBlock ap "
            + "where (ap.blocker.id = :viewerId and ap.blocked = c.parent.account) "
            + "or (ap.blocked.id = :viewerId and ap.blocker = c.parent.account))) ";

    /** 카드의 댓글 수 — 로그인 뷰어 기준(차단 반영). 비로그인은 {@link #countByPostIds}. */
    @Query("select c.post.id, count(c) from CommunityComment c "
            + "where c.post.id in :postIds and c.isDeleted = false " + NOT_BLOCKED
            + "group by c.post.id")
    List<Object[]> countByPostIdsForViewer(@Param("postIds") Collection<Long> postIds,
                                           @Param("viewerId") Long viewerId);

    /** 상세의 댓글 수 — 로그인 뷰어 기준(차단 반영). */
    @Query("select count(c) from CommunityComment c "
            + "where c.post.id = :postId and c.isDeleted = false " + NOT_BLOCKED)
    long countVisibleForViewer(@Param("postId") Long postId, @Param("viewerId") Long viewerId);

    /** 대댓글이 하나라도 있나 — 삭제를 soft 로 할지 hard 로 할지 가른다. */
    boolean existsByParentId(Long parentId);
}
