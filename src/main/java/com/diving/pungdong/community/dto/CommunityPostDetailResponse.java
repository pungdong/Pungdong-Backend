package com.diving.pungdong.community.dto;

import com.diving.pungdong.branding.CommunityCategory;
import com.diving.pungdong.branding.dto.LinkedCourseResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

/** 글 상세. 카드와 달리 본문 전체·미디어 전량·태그를 담는다. */
@Getter
@Builder
public class CommunityPostDetailResponse {

    private final Long id;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final CommunityCategory category;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String title;

    /** 본문 전체(클램프 없음). 브랜딩발 글은 caption 이 그대로 여기로 온다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String body;

    private final CommunityAuthorResponse author;

    private final List<Media> media;

    /**
     * 태그. <b>카드·상세 어디에도 렌더되지 않지만</b> 수정 폼 프리필에 필요해서 상세에만 싣는다
     * (웹 사이드바의 "인기 태그" 는 별도 집계 엔드포인트).
     */
    private final List<String> tags;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String locationLabel;

    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    private final long likeCount;
    private final long commentCount;
    private final long bookmarkCount;
    private final boolean likedByMe;
    private final boolean bookmarkedByMe;
    private final boolean hidden;

    /** 내 글이면 상세 상단 "더보기" 에 수정·삭제를 노출한다. 카드에는 메뉴가 없어 불필요. */
    private final boolean mine;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final LinkedCourseResponse linkedCourse;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final CommunityMatchResponse match;

    @Getter
    @Builder
    public static class Media {
        private final String url;
        private final int sortOrder;
    }
}
