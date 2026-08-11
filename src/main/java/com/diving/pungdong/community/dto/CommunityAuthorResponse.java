package com.diving.pungdong.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * 피드·상세·댓글에 공통으로 실리는 작성자. 강사 강조 UI(아바타 링 + ✓ + "강사 · 강의 N" 칩)의 <b>유일한
 * 소스</b>라 댓글 한 줄에도 같은 모양으로 들어간다.
 */
@Getter
@Builder
public class CommunityAuthorResponse {

    /** 공개 프로필 진입 키 — 클라이언트가 {@code GET /instructors/{nickName}} 으로 그대로 쓴다. */
    private final String nickName;

    /** 미설정이면 null — 클라이언트가 기본 아바타를 그린다. */
    private final String avatarUrl;

    /**
     * 강사 여부. <b>항상 내려간다</b>(생략하지 않는다) — 카드마다 분기하는 값이라 없으면 FE 가
     * "아직 안 온 것"과 "강사가 아닌 것"을 구분할 수 없다.
     *
     * <p>⚠️ {@code @JsonProperty} 가 없으면 Jackson 이 {@code instructor} 로 직렬화해 계약이 조용히 깨진다.
     */
    @JsonProperty("isInstructor")
    private final boolean isInstructor;

    /**
     * 공개 강의 수 — "강사 · 강의 N" 칩. <b>강사가 아니면 키 자체가 없다.</b>
     *
     * <p>브랜딩의 {@code products.lessons} 와 같은 규칙을 따른다 — 데모 시드 노출 설정({@code
     * SiteSettings.showSeededCourses})까지 동일하게 적용해서, 같은 강사의 프로필 강의 수와 커뮤니티 칩
     * 숫자가 어긋나지 않게 한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Integer lessonCount;
}
