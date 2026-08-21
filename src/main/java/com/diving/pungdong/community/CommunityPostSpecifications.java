package com.diving.pungdong.community;

import com.diving.pungdong.block.AccountBlock;
import com.diving.pungdong.branding.BrandingPost;
import com.diving.pungdong.branding.BrandingPostTag;
import com.diving.pungdong.branding.CommunityCategory;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * 커뮤니티 피드의 동적 필터. 레포 관례대로 {@code JpaSpecificationExecutor} + 형제 Specifications 클래스다
 * (QueryDSL 은 Phase 0.4 에 제거됐다). {@code CourseSpecifications} 와 같은 모양.
 *
 * <p><b>정렬은 여기서 다루지 않는다.</b> 클라이언트가 보낸 {@code sort} 를 그대로 태우면 임의 필드 정렬로
 * 내부 컬럼을 탐색하거나 인덱스 없는 정렬로 풀스캔을 유발할 수 있어, 서비스가 화이트리스트 enum 으로
 * 고정한다(브랜딩 그리드와 같은 원칙).
 */
public final class CommunityPostSpecifications {

    private CommunityPostSpecifications() {
    }

    /**
     * 피드에 보일 자격 — 노출 켜짐 + 숨김 아님.
     *
     * <p><b>브랜딩 페이지 발행 여부({@code accountBranding.isPublished})는 보지 않는다 — 의도된 결정이다.</b>
     * 프로필을 비공개로 돌리는 건 "내 포트폴리오를 감춘다" 는 뜻이지 "내가 커뮤니티에 남긴 대화를
     * 지운다" 는 뜻이 아니다(포럼 글이 포트폴리오 비공개와 함께 사라지면 스레드가 끊긴다).
     * 글을 내리고 싶으면 글 단위 숨김을 쓴다.
     * ⚠️ 그 상태에서 카드의 작성자를 누르면 프로필 진입이 400 이 된다 — 클라이언트가 graceful 하게
     * 처리해야 하는 지점이고, 이건 알려진 조합이다.
     * <b>범위가 좁아졌다(2026-08-21)</b>: 프로필은 이제 모든 살아있는 계정에 있어서(빈 프로필 200),
     * 위 400 은 <b>유저가 직접 비공개로 내린</b> 경우에만 남는다. 예전엔 "아직 아무것도 안 적은" 계정도
     * 400 이라 <b>댓글만 단 유저를 누르면 늘 400</b> 이었다.
     * <p>여기서 정의한 비공개의 뜻("포트폴리오를 감춘다")은 이 파일 밖에서도 기준이 된다 —
     * 강의 상세의 강사 카드가 tagline·bio 만 감추는 근거가 이 문단이다
     * ({@code branding.CourseInstructorSummaryAdapter}).
     *
     * <p>작성자 탈퇴 여부도 여기서 거르지 않는다. 탈퇴는 {@code account.isDeleted} 소프트 삭제 +
     * 30일 유예 후 익명화라 별도 축이고, 게시물 필터에 섞으면 조인이 하나 더 붙는다.
     * 탈퇴 계정 글 처리는 익명화 정책(account-deletion)이 정할 문제다.
     */
    public static Specification<BrandingPost> feedVisible() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("showInFeed")),
                cb.isFalse(root.get("isHidden")));
    }

    /**
     * 카테고리 필터. 파라미터가 {@code null} 이면 필터를 걸지 않는다(= "전체" 피드).
     *
     * <p>글의 카테고리 자체는 V31 이후 <b>NOT NULL</b> 이다 — 작성 폼이 통합되면서 두 쓰기 경로 모두
     * 카테고리를 요구하고, 기존 행은 backfill 했다. 그래서 "전체" 에만 뜨고 카테고리 칸에서는 실종되는
     * 글은 더 이상 없다.
     */
    public static Specification<BrandingPost> category(CommunityCategory category) {
        if (category == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    /**
     * 작성자 유형 필터 — 지금은 "강사 글" 하나다. {@code null} 이면 전체.
     *
     * <p><b>판정 축은 승인된 강사 신청</b>이다. 작성자 칩의 {@code isInstructor} 를 만드는
     * {@link CommunityAuthorComposer} 와 같은 근거를 써야 "강사 글" 필터 결과와 화면에 칩이 붙는 글이
     * 일치한다. 코스 소유 같은 다른 축을 쓰면 칩 없는 글이 필터에 걸리거나 그 반대가 된다.
     */
    public static Specification<BrandingPost> authoredBy(AuthorType authorType) {
        if (authorType != AuthorType.INSTRUCTOR) {
            return null;
        }
        return (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var application = sub.from(InstructorApplication.class);
            sub.select(application.get("id"))
                    .where(cb.and(
                            cb.equal(application.get("account").get("id"),
                                    root.get("branding").get("account").get("id")),
                            cb.equal(application.get("status"), InstructorApplicationStatus.APPROVED)));
            return cb.exists(sub);
        };
    }

    /**
     * 태그 필터 — 사이드바의 인기 태그를 눌렀을 때. {@code null}/공백이면 걸지 않는다.
     *
     * <p><b>조인이 아니라 {@code exists} 서브쿼리</b>다. 조인하면 글이 태그 수만큼 중복 행으로 나와
     * 페이지에 같은 글이 여러 번 뜨고 {@code totalElements} 도 부풀어 오른다.
     *
     * <p><b>정확 일치</b>다. 부분일치({@code LIKE})는 인덱스를 못 타는 데다, "제주" 로 "제주도여행" 까지
     * 끌려오는 건 태그 필터가 아니라 검색의 동작이다 — 그건 별개 피처다.
     */
    public static Specification<BrandingPost> tag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        return (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var postTag = sub.from(BrandingPostTag.class);
            sub.select(postTag.get("id"))
                    .where(cb.and(
                            cb.equal(postTag.get("post").get("id"), root.get("id")),
                            cb.equal(postTag.get("tag"), tag)));
            return cb.exists(sub);
        };
    }

    /**
     * 차단 필터 — 뷰어와 <b>어느 방향으로든</b> 차단 관계인 작성자의 글을 뺀다. {@code null}(비로그인)이면
     * 걸지 않는다.
     *
     * <p>{@link #authoredBy} 와 <b>같은 경로</b>({@code branding.account.id})를 타고 극성만 반대다.
     * 조인이 아니라 {@code exists} 인 이유도 태그 필터와 같다 — 조인하면 차단 행 수만큼 글이 중복돼
     * {@code totalElements} 가 부풀어 오른다.
     *
     * <p><b>양방향인 게 정책이다.</b> 내가 차단한 사람의 글도, 나를 차단한 사람의 글도 보이지 않는다.
     * 단방향이면 "차단했는데 그 사람이 내 글에 계속 댓글을 단다" 는 상태가 남는다.
     *
     * <p>⚠️ 이 필터는 <b>Specification 경로만</b> 덮는다. 피드에는 전용 쿼리 경로가 둘 더 있고
     * (인기순·같이가요) 그쪽은 {@code CommunityPostJpaRepo.BLOCK_FILTER} 로 같은 술어를 받는다 —
     * 한쪽만 고치면 그 탭에서만 차단이 새어 나온다.
     */
    public static Specification<BrandingPost> notBlockedFor(Long viewerId) {
        if (viewerId == null) {
            return null;
        }
        return (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var block = sub.from(AccountBlock.class);
            var authorId = root.get("branding").get("account").get("id");
            sub.select(block.get("id"))
                    .where(cb.or(
                            cb.and(cb.equal(block.get("blocker").get("id"), viewerId),
                                    cb.equal(block.get("blocked").get("id"), authorId)),
                            cb.and(cb.equal(block.get("blocked").get("id"), viewerId),
                                    cb.equal(block.get("blocker").get("id"), authorId))));
            return cb.not(cb.exists(sub));
        };
    }

    /** 특정 계정이 북마크한 글만 — "저장한 글" 목록. */
    public static Specification<BrandingPost> bookmarkedBy(Long accountId) {
        if (accountId == null) {
            return null;
        }
        return (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var bookmark = sub.from(CommunityPostBookmark.class);
            sub.select(bookmark.get("post").get("id"))
                    .where(cb.and(
                            cb.equal(bookmark.get("post").get("id"), root.get("id")),
                            cb.equal(bookmark.get("account").get("id"), accountId)));
            return cb.exists(sub);
        };
    }
}
