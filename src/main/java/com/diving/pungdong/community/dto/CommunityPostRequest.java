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
 * <p><b>작성 폼은 이 하나다(2026-08-18 통합).</b> 브랜딩 글도 커뮤니티 글이고, 다른 건 "내 프로필에도
 * 남길지"({@link #showOnProfile}) 하나뿐이라 작성 경로를 둘로 나눌 이유가 없다. 반대 방향(브랜딩 요청
 * {@code BrandingPostRequest} 에 카테고리·제목을 더하는 쪽)은 고르지 않았다 — 그러면 같은 폼이 분기해
 * 두 엔드포인트를 부르게 되고, 필드가 하나 늘 때마다 계약을 두 벌 고쳐야 한다.
 *
 * <p>{@code POST /branding/me/posts} 는 <b>구버전 앱 호환으로만</b> 남아 있다. 신규 작성·수정은 전부
 * 이 DTO 를 탄다.
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

    /**
     * 내 브랜딩(프로필) 그리드에도 남길지. <b>기본 {@code false}</b> — 안 보내면 커뮤니티 피드에만 올라간다.
     * {@code true} 면 예전 {@code POST /branding/me/posts} 로 쓴 글과 같은 상태가 된다.
     *
     * <p><b>수정(PUT)에서도 바뀐다</b> — 프로필에서 내리는 건 글 삭제가 아니라 이 값만 {@code false} 로
     * 가는 것이다(글·좋아요·댓글은 그대로 남고 커뮤니티에는 계속 보인다).
     *
     * <p>⚠️ 이 필드도 <b>스냅샷</b>이다(미디어·태그와 같은 관례). 수정 요청에서 빼면 JSON 기본값
     * {@code false} 로 읽혀 <b>프로필에서 내려간다</b> — FE 는 수정 폼에도 현재 값을 실어야 한다.
     *
     * <p>사진이 한 장도 없으면 {@code true} 로 못 켠다 — 브랜딩 그리드는 사진 타일이라 빈 타일이 된다
     * (서비스가 400).
     */
    private boolean showOnProfile;

    /** {@code category == MATCH} 일 때 필수. 아니면 무시된다. */
    @Valid
    private MatchRequest match;

    @Getter @Setter
    @NoArgsConstructor
    public static class MatchRequest {

        /**
         * 모집 일정.
         *
         * <p><b>"미래여야 한다" 는 제약이 여기(DTO)에 없는 건 의도다.</b> 이 규칙은 요청만 봐서는
         * 판정할 수 없다 — <b>저장된 일정과 같은지</b>에 따라 답이 갈리기 때문이다.
         * {@code @FutureOrPresent} 를 필드에 걸었더니 <b>일정이 이미 지난 모집글은 제목 오타조차
         * 고칠 수 없었다</b>(프리필한 과거 날짜를 그대로 되돌려 보내면 400). 규칙의 의도는
         * "과거 날짜로 <i>모집하지</i> 마라" 인데 "과거 모집글을 <i>손대지</i> 마라" 로 과잉 적용된 것이다.
         *
         * <p>그래서 검증을 서비스로 옮겼다 — <b>새로 잡는 일정일 때만</b> 미래를 요구한다
         * ({@code CommunityPostService.applyMatch}). 신규 작성과 "일정 변경" 은 그대로 막힌다.
         * 레포 규약(형식 검증은 DTO 에서)의 예외이며, 근거는 "요청만으로 판정 불가" 다.
         */
        @NotNull(message = "일정을 골라주세요.")
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
