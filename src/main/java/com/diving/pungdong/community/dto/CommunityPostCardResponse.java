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

    /** 브랜딩에서 올라온 글은 카테고리가 없을 수 있다 — 그런 글은 "전체" 피드에만 뜬다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final CommunityCategory category;

    /** 브랜딩발 글은 제목이 없을 수 있다. 카드가 제목을 조건부로 렌더하므로 null 이어도 깨지지 않는다. */
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

    /** 강사가 연결했을 때만. DRAFT·삭제된 코스면 키 자체가 없다(비공개 코스가 새면 안 된다). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final LinkedCourseResponse linkedCourse;

    /** MATCH 카테고리 글에만. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final CommunityMatchResponse match;
}
