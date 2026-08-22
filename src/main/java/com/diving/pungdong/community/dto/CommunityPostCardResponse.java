package com.diving.pungdong.community.dto;

import com.diving.pungdong.branding.CommunityCategory;
import com.diving.pungdong.branding.dto.LinkedCourseResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 피드 카드 한 장. 목록은 HAL {@code PagedModel} 로 나가고 배열 키는 {@code _embedded.posts} 다
 * (결과가 비면 그 키 자체가 없다).
 */
@Getter
@Builder
@Relation(collectionRelation = "posts")
public class CommunityPostCardResponse {

    private final Long id;

    /** V31 이후 <b>항상 있다</b> — 두 쓰기 경로 모두 카테고리를 요구하고 기존 행은 backfill 했다. */
    private final CommunityCategory category;

    /**
     * 제목. <b>여전히 없을 수 있다</b> — 구 브랜딩 경로({@code POST /branding/me/posts})는 제목을
     * 선택으로 받고, 그 시절 글도 남아 있다(통합 폼은 필수). 카드가 조건부로 렌더한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String title;

    /**
     * 본문 앞부분. 카드가 CSS 로 3줄 클램프를 걸기 때문에 <b>넉넉히</b> 보낸다 — 너무 짧게 자르면
     * 클램프가 무의미해지고 줄이 덜 찬다.
     */
    private final String bodyExcerpt;

    private final CommunityAuthorResponse author;

    /**
     * 앞 3장만. 카드 그리드가 3장 + "+N" 오버레이 구조라 전체 URL 을 실을 이유가 없다 —
     * 나머지는 상세에서 받는다.
     */
    private final List<String> thumbnailUrls;

    /** 전체 장수. "+N" 오버레이 계산용. */
    private final int mediaCount;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String locationLabel;

    /** UTC ISO-8601. "15분 전" 같은 상대시간은 클라이언트가 만든다 — BE 는 문자열을 만들지 않는다. */
    private final OffsetDateTime createdAt;

    /**
     * 마지막 수정 시각(수정 없으면 {@code createdAt} 과 같다). 웹 sitemap 의 {@code <lastmod>} 용
     * (BE #323) — 없으면 정확한 값을 얻으려고 <b>글마다 상세를 한 번 더</b> 불러야 했다.
     *
     * <p>⚠️ 본문·제목·분류 수정은 잡지만 <b>미디어·태그만 교체한 경우는 못 잡는다</b> — 자식 테이블만
     * 바뀌면 {@code branding_post} 행이 안 더러워져 {@code @PreUpdate} 가 안 뛴다. 근사값으로 충분하다는
     * 전제 위에 서 있다(정책은 {@code docs/features/seo-indexing.md}).
     */
    private final OffsetDateTime updatedAt;

    private final long likeCount;
    private final long commentCount;
    private final long bookmarkCount;

    /** 비로그인이면 전부 false. */
    private final boolean likedByMe;
    private final boolean bookmarkedByMe;

    /**
     * 숨김 상태. 공개 피드에서는 항상 false(숨긴 글은 애초에 안 나온다) —
     * <b>오너가 자기 글을 조회할 때만</b> true 가 올 수 있고, 그래야 "숨김" 배지와 토글 상태를 그릴 수 있다.
     */
    private final boolean hidden;

    /**
     * 내 프로필 그리드에도 올라간 글인지. "내가 쓴 글" 목록의 <b>"프로필 노출" 뱃지와 승격/강등 버튼</b>이
     * 이 값으로 그려진다 — 없으면 FE 가 현재 상태를 모른 채 토글을 그려야 한다.
     */
    private final boolean showOnProfile;

    /** 강사가 연결했을 때만. DRAFT·삭제된 코스면 키 자체가 없다(비공개 코스가 새면 안 된다). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final LinkedCourseResponse linkedCourse;

    /** MATCH 카테고리 글에만. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final CommunityMatchResponse match;
}
