package com.diving.pungdong.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * "내 글에 달린 댓글" 의 술어. 아래 두 쿼리(목록·건수)가 <b>같은 문자열</b>을 쓴다 —
     * 따로 적으면 한쪽만 고쳐져 목록은 3건인데 {@code totalElements} 는 5 가 된다.
     *
     * <p>네 갈래를 모두 뺀다.
     * <ul>
     *   <li><b>숨긴 글</b>({@code isHidden}) — 작성자가 내렸거나 어드민이 조치한 글이다. 커뮤니티 피드와
     *       브랜딩 그리드 양쪽에서 빠지는 글의 댓글이 프로필 카드에만 남으면, 신고로 내려간 글이
     *       거기서 계속 살아 있는 셈이 된다.</li>
     *   <li><b>삭제된 댓글</b> — 미리보기에 "삭제된 댓글입니다." 가 뜨면 안 된다. 스레드와 달리
     *       여기선 자리를 지킬 이유가 없다(부모 자리를 대신할 자식이 없는 평면 목록이다).</li>
     *   <li><b>내가 쓴 댓글</b> — 내 글에 내가 단 댓글이 "최근 반응" 으로 올라오면 자기 반응을 남의
     *       반응으로 읽는다. 알림 발행이 자기 자신을 거르는 것과 같은 기조다.</li>
     *   <li><b>차단 관계</b>({@link #NOT_BLOCKED}) — 카드의 {@code commentCount} 와 같은 기준이라야
     *       "댓글 3" 인데 미리보기 목록엔 2건인 상태가 생기지 않는다.</li>
     * </ul>
     *
     * <p>글 주인이 곧 뷰어라 파라미터가 {@code :viewerId} 하나다 — 이 목록은 {@code /me} 경로에만 있고,
     * 남의 글에 달린 댓글을 이 쿼리로 볼 방법은 없다(그게 열리면 그대로 사찰 도구가 된다).
     */
    String ON_MY_POSTS = "where c.post.branding.account.id = :viewerId "
            + "and c.post.isHidden = false and c.isDeleted = false "
            + "and c.account.id <> :viewerId " + NOT_BLOCKED;

    /**
     * 내 글에 달린 댓글 — <b>최신순 고정</b>. 정렬 파라미터는 없다(이 목록의 정의가 "가장 최근" 이다).
     *
     * <p><b>왜 서버가 모아줘야 하나.</b> 클라이언트가 만들려면 내 글을 전부 돌며 글마다 스레드를
     * 조회해야 하는데(N+1), "어느 글에 방금 댓글이 달렸는지" 를 알 방법이 없어 <b>최신순을 보장할 수
     * 없다</b> — 오래된 글에 달린 새 댓글을 놓친다.
     *
     * <p>대댓글도 담는다. 내 글에 달린 반응이라는 점에서 최상위 댓글과 다르지 않다.
     *
     * <p>인덱스는 기존 {@code ix_community_comment_thread(post_id, created_at)} 와
     * {@code ix_branding_post_grid(branding_id, ...)} 로 충분하다. 전역 최신순 정렬에 filesort 가 붙지만
     * 대상이 "내 글의 댓글" 로 좁아서 지금 규모에선 의미 없는 비용이고, 없애려면 댓글에 글 작성자 id 를
     * 비정규화해야 하는데 <b>이 도메인은 카운터·비정규화를 두지 않는다</b>(동기화가 어긋날 경로만 는다).
     */
    @Query(value = "select c from CommunityComment c " + ON_MY_POSTS
            + "order by c.createdAt desc, c.id desc",
            countQuery = "select count(c) from CommunityComment c " + ON_MY_POSTS)
    Page<CommunityComment> findOnMyPosts(@Param("viewerId") Long viewerId, Pageable pageable);

    /** 대댓글이 하나라도 있나 — 삭제를 soft 로 할지 hard 로 할지 가른다. */
    boolean existsByParentId(Long parentId);
}
