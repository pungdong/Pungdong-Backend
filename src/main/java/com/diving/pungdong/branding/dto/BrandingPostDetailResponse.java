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

    /**
     * 숨김 상태. <b>공개 조회에서는 항상 false</b> — 숨긴 글은 오너에게만 열리기 때문이다.
     * 커뮤니티 상세({@code CommunityPostDetailResponse.hidden})와 같은 의미이고, 같은 행의 같은 컬럼이다.
     *
     * <p>없으면 오너 액션시트가 <b>이미 숨긴 글에 "숨기기" 를 표시</b>한다 — 커뮤니티에서 숨긴 뒤
     * 브랜딩 화면으로 넘어오는 경로가 실제로 있어서(커뮤니티 메뉴가 브랜딩발 글의 삭제를 그쪽으로 보낸다)
     * 상태를 모른 채 토글을 그리게 된다.
     */
    @JsonProperty("hidden")
    private boolean hidden;

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
