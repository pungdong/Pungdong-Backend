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
            + "order by m.meetDate asc, p.id desc")
    Page<BrandingPost> findMatchFeed(Pageable pageable);

    /**
     * 인기순 피드 — 최근 {@code since} 이후 글을 좋아요 많은 순으로.
     *
     * <p><b>기간을 자르는 이유</b>: 자르지 않으면 한 번 인기를 얻은 오래된 글이 영구히 상단을 차지해
     * 피드가 굳는다. 최근 창 안에서만 겨루게 해야 새 글에 기회가 간다.
     *
     * <p>{@code group by p}(엔티티 전체)로 묶는 건 H2·MySQL 양쪽에서 안전하기 때문이다 —
     * {@code group by p.id} 는 DB 의 {@code ONLY_FULL_GROUP_BY} 해석에 따라 갈린다.
     * 정렬 tie-break 은 {@code id desc} 라 좋아요 수가 같아도 페이지 경계가 흔들리지 않는다.
     *
     * <p>{@code countQuery} 를 따로 준 이유: group by 가 붙은 쿼리를 그대로 count 로 감싸면
     * "그룹 수" 가 아니라 행 수가 나와 총 개수가 어긋난다.
     */
    @Query(value = "select p from BrandingPost p left join CommunityPostLike l on l.post.id = p.id "
            + "where p.showInFeed = true and p.isHidden = false and p.createdAt >= :since "
            + "group by p order by count(l) desc, p.id desc",
            countQuery = "select count(p) from BrandingPost p "
                    + "where p.showInFeed = true and p.isHidden = false and p.createdAt >= :since")
    Page<BrandingPost> findPopularFeed(@Param("since") OffsetDateTime since, Pageable pageable);

    /** 위와 같되 카테고리로 좁힌다. */
    @Query(value = "select p from BrandingPost p left join CommunityPostLike l on l.post.id = p.id "
            + "where p.showInFeed = true and p.isHidden = false and p.createdAt >= :since "
            + "and p.category = :category "
            + "group by p order by count(l) desc, p.id desc",
            countQuery = "select count(p) from BrandingPost p "
                    + "where p.showInFeed = true and p.isHidden = false and p.createdAt >= :since "
                    + "and p.category = :category")
    Page<BrandingPost> findPopularFeedByCategory(@Param("since") OffsetDateTime since,
                                                 @Param("category") com.diving.pungdong.branding.CommunityCategory category,
                                                 Pageable pageable);

    /**
     * 카테고리별 최근 7일 글 수 — 피드 상단 4-up 그리드와 HOT 뱃지(&gt;50).
     * 카테고리가 없는 글(브랜딩발)은 어느 칸에도 속하지 않으므로 제외한다.
     */
    @Query("select p.category, count(p) from BrandingPost p "
            + "where p.showInFeed = true and p.isHidden = false "
            + "and p.category is not null and p.createdAt >= :since "
            + "group by p.category")
    List<Object[]> countByCategorySince(@Param("since") OffsetDateTime since);

    /**
     * 인기 태그 — 웹 좌측 sidebar. 태그는 JSON 이 아니라 자식 행이라 group by 가 그대로 먹는다.
     * 반환은 {@code [tag, count]} 이고 정렬은 건수 내림차순.
     */
    @Query("select t.tag, count(t) from BrandingPostTag t "
            + "where t.post.showInFeed = true and t.post.isHidden = false "
            + "group by t.tag order by count(t) desc, t.tag asc")
    List<Object[]> countPopularTags(Pageable pageable);

    /**
     * 관련 글 — 웹 상세 우측 rail. 같은 카테고리, 자기 자신 제외, 최신순.
     * 카테고리가 없는 글에는 관련 글이 없다(호출부가 빈 목록으로 처리).
     */
    @Query("select p from BrandingPost p "
            + "where p.showInFeed = true and p.isHidden = false "
            + "and p.category = :category and p.id <> :excludePostId "
            + "order by p.createdAt desc, p.id desc")
    List<BrandingPost> findRelated(@Param("category") com.diving.pungdong.branding.CommunityCategory category,
                                   @Param("excludePostId") Long excludePostId,
                                   Pageable pageable);
}
