package com.diving.pungdong.community.dto;

import com.diving.pungdong.branding.CommunityCategory;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.Valid;
import javax.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 커뮤니티 글 작성·수정. 수정도 <b>스냅샷 교체</b>다 — 보낸 {@code mediaUrls}/{@code tags} 가 최종 상태다
 * (브랜딩 게시물·기록·course 와 같은 관례).
 *
 * <p>브랜딩 작성 경로({@code BrandingPostRequest})와 필드가 겹치지만 <b>합치지 않는다.</b> 두 경로의
 * 필수 조건이 다르다 — 커뮤니티는 카테고리·제목이 필수고 사진이 선택인데, 브랜딩은 사진이 필수고
 * 카테고리는 FE 가 강제한다. 한 DTO 에 담으면 어느 쪽 규칙인지 검증 애노테이션으로 표현할 수 없다.
 */
@Getter @Setter
@NoArgsConstructor
public class CommunityPostRequest {

    @NotNull(message = "카테고리를 골라주세요.")
    private CommunityCategory category;

    @NotBlank(message = "제목을 입력해주세요.")
    @Size(min = 2, max = 100, message = "제목은 2자 이상 100자까지 쓸 수 있어요.")
    private String title;

    /** 본문은 선택 — 제목 + 사진만 올리는 자랑 글이 자연스럽다. 상한은 디자인 명시값(5000). */
    @Size(max = 5000, message = "본문은 5000자까지 쓸 수 있어요.")
    private String body;

    /**
     * 업로드로 받은 우리 CDN URL 들. 배열 순서 = 표시 순서, 0번이 썸네일.
     *
     * <p>사진 없는 글을 허용한다 — 궁금해요·같이가요는 텍스트만으로 성립한다. 카테고리별로 사진을
     * 강제하지 않는 이유는 규칙만 복잡해지고 실익이 없기 때문.
     */
    @NotNull(message = "사진 목록은 비워 보내더라도 배열이어야 해요.")
    @Size(max = 10, message = "사진은 10장까지 올릴 수 있어요.")
    private List<String> mediaUrls = new ArrayList<>();

    /** 상한은 디자인 명시값(5개). */
    @NotNull(message = "태그 목록은 비워 보내더라도 배열이어야 해요.")
    @Size(max = 5, message = "태그는 5개까지 달 수 있어요.")
    private List<@Size(min = 1, max = 30, message = "태그는 30자까지 쓸 수 있어요.") String> tags = new ArrayList<>();

    @Size(max = 60, message = "위치는 60자까지 쓸 수 있어요.")
    private String locationLabel;

    /**
     * 연결할 내 강의. <b>강사만</b> 보낼 수 있고 <b>내 코스</b>여야 한다.
     * MATCH 카테고리 글에는 연결할 수 없다(영리활동 금지 가드).
     */
    private Long linkedCourseId;

    /** {@code category == MATCH} 일 때 필수. 아니면 무시된다. */
    @Valid
    private MatchRequest match;

    @Getter @Setter
    @NoArgsConstructor
    public static class MatchRequest {

        @NotNull(message = "일정을 골라주세요.")
        @FutureOrPresent(message = "지난 날짜로는 모집할 수 없어요.")
        private LocalDate meetDate;

        /** 입수 시각. 날짜만 정하고 시간은 협의하는 모집도 있어 선택. */
        private LocalTime meetTime;

        @NotNull(message = "모집 인원을 입력해주세요.")
        @Min(value = 2, message = "모집 인원은 2명 이상이어야 해요.")
        @Max(value = 20, message = "모집 인원은 20명까지예요.")
        private Integer capacity;

        /** brief 가 "일정/인원/레벨 필수" 로 못박아서 요구 자격도 필수다. */
        @NotBlank(message = "요구 자격을 입력해주세요.")
        @Size(max = 60, message = "요구 자격은 60자까지 쓸 수 있어요.")
        private String levelLabel;
    }
}
