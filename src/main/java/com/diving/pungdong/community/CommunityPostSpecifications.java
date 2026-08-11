package com.diving.pungdong.community;

import com.diving.pungdong.branding.BrandingPost;
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
     * 카테고리 필터. {@code null} 이면 전체 — 이때 <b>카테고리 없는 글도 포함</b>된다.
     *
     * <p>브랜딩에서 올라온 글은 카테고리가 없을 수 있어서, 4-up 그리드로 특정 카테고리에 진입하면
     * 그 글들은 빠지고 "전체" 피드에서만 보인다. 없는 값을 임의 카테고리로 채우지 않는다.
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
