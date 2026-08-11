package com.diving.pungdong.branding.dto;

import com.diving.pungdong.branding.CommunityCategory;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * 게시물 작성·수정 — 수정도 <b>스냅샷 교체</b>다(미디어·태그를 통째로 갈아끼운다). 사진 순서 변경이
 * 일상 동작이라 부분 갱신보다 원자적 교체가 안전하다(기록·course 와 같은 관례).
 *
 * <p><b>브랜딩에 올린 글은 커뮤니티 피드에도 새 글로 나간다</b>(노출은 브랜딩 → 커뮤니티 단방향).
 * 그래서 피드 카드가 필요로 하는 {@code category} 를 여기서 함께 받는다.
 */
@Getter @Setter
@NoArgsConstructor
public class BrandingPostRequest {

    /**
     * 커뮤니티 카테고리. 없으면 4-up 그리드(카테고리 필터)에서 실종돼 "커뮤니티에도 올라갔다"는
     * 요구가 반쪽이 되므로, <b>신규 글에는 사실상 필수</b>다. 다만 <b>필수 강제는 FE 가 한다.</b>
     *
     * <p><b>왜 BE 가 {@code @NotNull} 을 걸지 않나 — 배포 순서 때문이다.</b> 이 레포는 BE 를 먼저
     * 배포한다. BE 가 카테고리를 강제하는 순간, 아직 이 필드를 보내지 않는 <b>배포된 브랜딩 FE 의
     * 게시물 작성이 전부 400</b> 이 된다. FE 가 따라올 때까지 살아 있는 기능이 죽는다.
     * (레포 규칙 "FE 검증은 UX 지 경계가 아니다" 와 상충하지 않는다 — 여기서 null 은 잘못된 입력이
     * 아니라 <b>정당한 값</b>이다. V19 이전 글과 배포 창(window) 중의 글이 그렇다.)
     *
     * <p>기본값을 자동으로 넣지 않는 이유: 하이라이트가 전부 "투어 자랑"은 아니다. 임의로 채우면
     * 잘못된 카테고리로 필터에 잡혀 피드 품질이 깎인다 — 없는 값을 지어내지 않는다는 레포 원칙.
     *
     * <p>FE 가 양 플랫폼에 배포된 뒤 {@code @NotNull} 로 조일 수 있다. 그때가 되면 이 주석도 지운다.
     */
    private CommunityCategory category;

    /**
     * 제목. <b>선택</b>이다 — 브랜딩 글은 caption 이 곧 본문이라, 필수로 만들면 유저가 같은 말을 두 번
     * 쓰거나 대충 채운다. 긴 후기를 쓰는 사람에게 선택지만 준다.
     *
     * <p>비워도 피드 카드가 깨지지 않는다 — {@code PostCard} 가 제목을 조건부로 렌더한다.
     */
    @Size(max = 100, message = "제목은 100자까지 쓸 수 있어요.")
    private String title;

    /**
     * 업로드해서 받은 CDN URL 들. <b>배열 순서가 곧 표시 순서</b>이고 <b>0번이 그리드 썸네일</b>이다.
     * 우리 CDN base 로 시작하는지 서버가 검증한다 — 임의 외부 URL 을 본문에 심지 못하게.
     */
    @NotNull(message = "사진을 한 장 이상 올려주세요.")
    @Size(min = 1, max = 10, message = "사진은 1장 이상 10장까지 올릴 수 있어요.")
    private List<String> mediaUrls = new ArrayList<>();

    @Size(max = 2000, message = "본문은 2000자까지 쓸 수 있어요.")
    private String caption;

    @NotNull(message = "태그 목록은 비워 보내더라도 배열이어야 해요.")
    @Size(max = 10, message = "태그는 10개까지 달 수 있어요.")
    private List<@Size(min = 1, max = 30, message = "태그는 30자까지 쓸 수 있어요.") String> tags = new ArrayList<>();

    @Size(max = 60, message = "위치는 60자까지 쓸 수 있어요.")
    private String locationLabel;

    /** 연결할 내 강의. 강사가 아니거나 남의 코스면 400. 안 보내면 연결 없음. */
    private Long linkedCourseId;
}
