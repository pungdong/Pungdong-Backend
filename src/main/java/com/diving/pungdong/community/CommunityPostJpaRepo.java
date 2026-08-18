package com.diving.pungdong.community;

import com.diving.pungdong.branding.BrandingPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 커뮤니티 관점의 게시물 조회. 엔티티는 브랜딩과 <b>같은 {@link BrandingPost}</b> 를 쓴다 —
 * 테이블이 하나이고 노출 플래그로 두 화면을 가르기 때문(엔티티 Javadoc 참고).
 *
 * <p>{@code BrandingPostJpaRepo} 와 별도로 두는 이유: 같은 엔티티라도 <b>조회 축이 다르다.</b>
 * 브랜딩은 항상 {@code branding_id} 로 좁힌 뒤 프로필 그리드를 만들고, 커뮤니티는 작성자와 무관하게
 * 전체 피드를 훑는다. 한 인터페이스에 섞으면 어느 쿼리가 어느 화면 것인지 읽어서 알 수 없다.
 *
 * <p>피드의 동적 필터(카테고리·작성자 유형)는 {@link CommunityPostSpecifications} 로 조립한다 —
 * 레포 관례가 QueryDSL 이 아니라 {@code JpaSpecificationExecutor} + 형제 Specifications 클래스다.
 */
public interface CommunityPostJpaRepo extends JpaRepository<BrandingPost, Long>,
        JpaSpecificationExecutor<BrandingPost> {

    /**
     * 참여 점수 — <b>좋아요 + 댓글 + 북마크</b>. 인기순 피드(전체·카테고리)와 트렌딩 토픽이
     * <b>같은 식</b>을 써야 해서 상수로 뺐다. 다르면 "인기 탭 1등 ≠ 지금 뜨는 토픽 1등" 이라는
     * 눈에 보이는 모순이 생긴다.
     *
     * <p><b>{@code distinct} 가 필수다.</b> 세 축을 한 번에 조인하면 행이 곱해진다
     * (좋아요 10 × 댓글 5 × 북마크 3 = 150행). 그냥 {@code count()} 면 그 곱이 그대로 점수가 된다.
     * {@code count(distinct 자식.id)} 여야 각 축이 제 개수로 센다.
     */
    String ENGAGEMENT_SCORE = "(count(distinct l.id) + count(distinct c.id) + count(distinct b.id))";

    /**
     * 위 점수식이 참조하는 세 조인. <b>삭제된 댓글은 {@code on} 절에서 뺀다</b> —
     * 카드의 댓글 수({@code CommunityCommentJpaRepo.countByPostIds})와 기준이 갈리면
     * "댓글 3개인데 점수엔 5개" 가 된다.
     */
    String ENGAGEMENT_JOINS = "left join CommunityPostLike l on l.post.id = p.id "
            + "left join CommunityComment c on c.post.id = p.id and c.isDeleted = false "
            + "left join CommunityPostBookmark b on b.post.id = p.id ";

    /** 노출 술어 + 기간 창. 인기순 피드가 본문과 {@code countQuery} 에서 같은 조건을 써야 해서 뺐다. */
    String FEED_VISIBLE = "p.showInFeed = true and p.isHidden = false and p.createdAt >= :since ";

    /** 웹 피드의 "강사 글" pill. 생략(false)이면 전체다. */
    String INSTRUCTOR_FILTER = "and (:instructorOnly = false or exists (select 1 from InstructorApplication ia "
            + "where ia.account = p.branding.account "
            + "and ia.status = com.diving.pungdong.instructorapplication.InstructorApplicationStatus.APPROVED)) ";

    /**
     * 태그 필터. <b>조인이 아니라 {@code exists}</b> 다 — 조인하면 한 글이 태그 수만큼 중복 행으로
     * 나와 페이징(total·페이지 경계)이 어긋난다. 부분일치는 하지 않는다(그건 검색이다).
     */
    String TAG_FILTER = "and (:tag is null or exists (select 1 from BrandingPostTag t where t.post = p and t.tag = :tag)) ";

    /**
     * 피드에 노출 중인 글 1건. 숨김·미노출 글은 <b>없는 것으로 취급</b>한다(존재 숨김 → 호출부가 400).
     * 오너 본인에게 열어주는 분기는 서비스가 별도로 처리한다.
     */
    @Query("select p from BrandingPost p "
            + "where p.id = :postId and p.showInFeed = true and p.isHidden = false")
    Optional<BrandingPost> findVisibleInFeed(@Param("postId") Long postId);

    /** 내 글 1건(숨김 포함) — 수정·삭제 전 소유권 확인용. 남의 글이면 빈 Optional → 400. */
    @Query("select p from BrandingPost p "
            + "where p.id = :postId and p.branding.account.id = :accountId")
    Optional<BrandingPost> findMine(@Param("postId") Long postId, @Param("accountId") Long accountId);

    /**
     * 내가 쓴 글 전부 — "내가 쓴 글" 화면. <b>숨김도 프로필 미노출도 포함</b>한다.
     *
     * <p><b>왜 별도 목록이 필요한가.</b> 오너가 자기 글을 볼 수 있는 목록이 브랜딩 오너 그리드
     * 하나뿐이었는데 그건 {@code showOnProfile=true} 만 담는다. 그래서 "숨김 + 커뮤니티 전용" 글은
     * 어느 목록에도 없어 <b>상세 URL 을 아는 사람만</b> 되돌릴 수 있었다 — 되돌릴 화면이 없으면
     * 그건 숨김이 아니라 사실상 삭제다.
     *
     * <p>정렬은 최신순 + id tie-break(페이지 경계 안정). 필터가 계정 하나라 인덱스는 기존
     * {@code ix_branding_post_grid(branding_id, ...)} 로 충분하다.
     */
    @Query("select p from BrandingPost p where p.branding.account.id = :accountId "
            + "order by p.createdAt desc, p.id desc")
    Page<BrandingPost> findAllMine(@Param("accountId") Long accountId, Pageable pageable);

    /**
     * 같이가요 피드 — <b>일정 임박순</b>이 기본 정렬이다.
     *
     * <p>정렬 키가 게시물이 아니라 조인 테이블({@code community_post_match.meet_date})에 있어서
     * Specification 으로 조립하지 않고 전용 쿼리로 뺐다. Specification 에 조인 정렬을 끼워 넣으면
     * "카테고리에 따라 정렬 축이 바뀐다"는 사실이 조립 코드 속에 숨어 읽히지 않는다.
     *
     * <p>클라이언트가 이 정렬을 고를 수단은 없다 — 디자인의 "일정 임박순" pill 이 있는 화면이
     * Phase 1 범위 밖이라, {@code sort} enum 에 죽은 값을 남기는 대신 카테고리 기본값으로만 살렸다.
     */
    @Query("select p from BrandingPost p join CommunityPostMatch m on m.postId = p.id "
            + "where p.showInFeed = true and p.isHidden = false "
            + "and p.category = com.diving.pungdong.branding.CommunityCategory.MATCH "
            + "and (:instructorOnly = false or exists (select 1 from InstructorApplication ia where ia.account = p.branding.account and ia.status = com.diving.pungdong.instructorapplication.InstructorApplicationStatus.APPROVED)) "
            + TAG_FILTER
            + "order by m.meetDate asc, p.id desc")
    Page<BrandingPost> findMatchFeed(@Param("instructorOnly") boolean instructorOnly,
                                     @Param("tag") String tag,
                                     Pageable pageable);

    /**
     * 인기순 피드 — 최근 {@code since} 이후 글을 <b>참여 점수</b>(좋아요+댓글+북마크) 높은 순으로.
     *
     * <p><b>기간을 자르는 이유</b>: 자르지 않으면 한 번 인기를 얻은 오래된 글이 영구히 상단을 차지해
     * 피드가 굳는다. 최근 창 안에서만 겨루게 해야 새 글에 기회가 간다.
     *
     * <p><b>좋아요만 세지 않는 이유</b>: 사이드바 "지금 뜨는 토픽" 과 순위 기준이 갈리면 안 된다.
     * 같은 화면에서 1등이 서로 다르면 둘 중 하나는 고장 난 것으로 읽힌다.
     *
     * <p>{@code group by p}(엔티티 전체)로 묶는 건 H2·MySQL 양쪽에서 안전하기 때문이다 —
     * {@code group by p.id} 는 DB 의 {@code ONLY_FULL_GROUP_BY} 해석에 따라 갈린다.
     * 정렬 tie-break 은 {@code id desc} 라 점수가 같아도 페이지 경계가 흔들리지 않는다.
     *
     * <p>{@code countQuery} 를 따로 준 이유: group by 가 붙은 쿼리를 그대로 count 로 감싸면
     * "그룹 수" 가 아니라 행 수가 나와 총 개수가 어긋난다. <b>세 축을 조인한 지금은 그 어긋남이
     * 훨씬 크다</b>(카티전 곱만큼).
     */
    @Query(value = "select p from BrandingPost p " + ENGAGEMENT_JOINS
            + "where " + FEED_VISIBLE + INSTRUCTOR_FILTER + TAG_FILTER
            + "group by p order by " + ENGAGEMENT_SCORE + " desc, p.id desc",
            countQuery = "select count(p) from BrandingPost p "
                    + "where " + FEED_VISIBLE + INSTRUCTOR_FILTER + TAG_FILTER)
    Page<BrandingPost> findPopularFeed(@Param("since") OffsetDateTime since,
                                       @Param("instructorOnly") boolean instructorOnly,
                                       @Param("tag") String tag,
                                       Pageable pageable);

    /** 위와 같되 카테고리로 좁힌다. */
    @Query(value = "select p from BrandingPost p " + ENGAGEMENT_JOINS
            + "where " + FEED_VISIBLE + "and p.category = :category " + INSTRUCTOR_FILTER + TAG_FILTER
            + "group by p order by " + ENGAGEMENT_SCORE + " desc, p.id desc",
            countQuery = "select count(p) from BrandingPost p "
                    + "where " + FEED_VISIBLE + "and p.category = :category " + INSTRUCTOR_FILTER + TAG_FILTER)
    Page<BrandingPost> findPopularFeedByCategory(@Param("since") OffsetDateTime since,
                                                 @Param("category") com.diving.pungdong.branding.CommunityCategory category,
                                                 @Param("instructorOnly") boolean instructorOnly,
                                                 @Param("tag") String tag,
                                                 Pageable pageable);

    /**
     * 지금 뜨는 토픽 — 웹 우측 sidebar. 인기순 피드와 <b>같은 점수·같은 창</b>의 상위 N 건이다.
     *
     * <p>반환은 {@code [postId, title, category, score]} 뿐이다. 사이드바는 순위·제목·숫자만
     * 그리므로 카드 매퍼(썸네일·작성자·본문 발췌·같이가요 정보)를 태울 이유가 없다 —
     * 그러려면 미디어·작성자를 일괄 조회하는 쿼리가 통째로 따라붙는다.
     *
     * <p>결과가 요청한 개수보다 적으면 <b>적은 대로</b> 돌려준다. 카테고리 카운트처럼 0 으로 채우는 건
     * 여기선 틀린다 — 없는 글을 지어낼 수는 없다.
     */
    @Query("select p.id, p.title, p.category, " + ENGAGEMENT_SCORE + " from BrandingPost p " + ENGAGEMENT_JOINS
            + "where p.showInFeed = true and p.isHidden = false and p.createdAt >= :since "
            + "group by p order by " + ENGAGEMENT_SCORE + " desc, p.id desc")
    List<Object[]> findTrendingTopics(@Param("since") OffsetDateTime since, Pageable pageable);

    /**
     * 카테고리별 최근 7일 글 수 — 피드 상단 4-up 그리드와 HOT 뱃지(&gt;50).
     * 카테고리는 V31 이후 NOT NULL 이라 모든 글이 정확히 한 칸에 속한다(예전엔 브랜딩발 글이
     * 어느 칸에도 안 속해 `category is not null` 로 걸러야 했다).
     */
    @Query("select p.category, count(p) from BrandingPost p "
            + "where p.showInFeed = true and p.isHidden = false "
            + "and p.createdAt >= :since "
            + "group by p.category")
    List<Object[]> countByCategorySince(@Param("since") OffsetDateTime since);

    /**
     * 인기 태그 — 웹 좌측 sidebar. 태그는 JSON 이 아니라 자식 행이라 group by 가 그대로 먹는다.
     * 반환은 {@code [tag, count]} 이고 정렬은 건수 내림차순.
     *
     * <p><b>세는 단위는 태그 행이 아니라 글이다</b>({@code count(distinct t.post.id)}).
     * {@code branding_post_tag} 에는 {@code (post_id, tag)} UNIQUE 가 없어서(V17) 한 글이 같은 태그를
     * 두 번 담으면 행으로 세던 예전 식은 2 로 셌다. 쓰기 경로에서도 중복을 걷어내지만
     * <b>이미 저장된 중복까지 방어</b>하려면 여기서 distinct 여야 한다(그래서 마이그레이션이 필요 없다).
     *
     * <p><b>기간을 자르는 이유</b>는 인기순 피드와 같다 — 전체 기간이면 초기에 달린 태그가 영구히
     * 상단에 굳어 "인기" 가 아니라 "최초" 를 보여주게 된다. 창이 붙으면서 {@code branding_post} 조인이
     * 생겨 {@code ix_branding_post_tag_tag} 커버링은 깨지지만, 창으로 좁힌 뒤 {@code post_id} 로 붙는
     * 형태라 지금 데이터 규모에서 문제될 양이 아니다(필요해지면 실측하고 인덱스를 붙인다).
     */
    @Query("select t.tag, count(distinct t.post.id) from BrandingPostTag t "
            + "where t.post.showInFeed = true and t.post.isHidden = false "
            + "and t.post.createdAt >= :since "
            + "group by t.tag order by count(distinct t.post.id) desc, t.tag asc")
    List<Object[]> countPopularTags(@Param("since") OffsetDateTime since, Pageable pageable);

    /**
     * 관련 글 — 웹 상세 우측 rail. 같은 카테고리, 자기 자신 제외, 최신순.
     * 같은 카테고리 글이 없으면 빈 목록이다(호출부가 그대로 내려보낸다).
     */
    @Query("select p from BrandingPost p "
            + "where p.showInFeed = true and p.isHidden = false "
            + "and p.category = :category and p.id <> :excludePostId "
            + "order by p.createdAt desc, p.id desc")
    List<BrandingPost> findRelated(@Param("category") com.diving.pungdong.branding.CommunityCategory category,
                                   @Param("excludePostId") Long excludePostId,
                                   Pageable pageable);
}
