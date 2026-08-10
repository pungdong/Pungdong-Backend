package com.diving.pungdong.branding.dto;

import com.diving.pungdong.branding.BrandingMediaKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 게시물 상세 — 캐로셀 + 본문 + 태그 + 연결 강의.
 *
 * <p>{@code createdAt} 은 <b>UTC ISO-8601</b> 그대로 준다. 디자인의 "하루 전" 같은 상대시간 문자열은
 * BE 가 만들지 않는다 — 뷰어 로케일·표시 정책은 FE 결정이고, 서버가 포맷을 재구현하면 어긋난다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class BrandingPostDetailResponse {

    private Long id;
    private Author author;
    private List<Media> media;

    private String caption;
    private List<String> tags;
    private String locationLabel;

    private OffsetDateTime createdAt;

    @JsonProperty("pinned")
    private boolean pinned;

    /** 강사가 연결했을 때만. 미공개·삭제된 코스면 키가 빠진다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LinkedCourseResponse linkedCourse;

    @Getter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class Author {
        private String nickName;
        private String avatarUrl;
    }

    @Getter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class Media {
        private BrandingMediaKind kind;
        private String url;
        private int sortOrder;
    }
}
