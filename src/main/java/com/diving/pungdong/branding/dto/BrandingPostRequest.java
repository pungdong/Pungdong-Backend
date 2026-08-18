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
 * <p><b>이 경로는 구버전 앱 호환으로만 남아 있다(2026-08-18).</b> 신규 작성·수정은 통합 폼
 * {@code POST|PUT /community/posts}({@code CommunityPostRequest.showOnProfile=true})가 대신한다 —
 * 같은 행을 만들고 규칙(같이가요 강의연결 금지 등)까지 한곳에서 검사한다. 여기에 필드를 더하기 전에
 * <b>통합 폼으로 되는 일인지 먼저 확인할 것.</b>
 *
 * <p>여기서 온 글도 커뮤니티 피드에 새 글로 나간다({@code showInFeed=true}, {@code showOnProfile=true}).
 */
@Getter @Setter
@NoArgsConstructor
public class BrandingPostRequest {

    /**
     * 커뮤니티 카테고리. <b>필수</b>다(2026-08-18) — 모든 글은 커뮤니티 글이라 분류축 없이는
     * 4-up 그리드에서 실종되고, 나중에 수정하려는 작성자가 없던 카테고리를 발명해야 한다.
     *
     * <p><b>예전엔 선택이었다 — 배포 순서 때문이었다.</b> BE 가 먼저 배포되므로 카테고리를 강제하면
     * 아직 이 필드를 안 보내는 FE 의 게시물 작성이 전부 400 이 됐다. 그 창은 닫혔다: 신규 작성은 전부
     * 통합 폼({@code POST /community/posts} + {@code showOnProfile})으로 가고, 이 엔드포인트는
     * <b>구버전 앱 호환</b>으로만 남는다. 게다가 이 엔드포인트는 <b>프로덕션에 배포된 적이 없다</b>
     * (prod 최종 배포 a383968 에는 V17/V19 자체가 없다) — 구버전 앱에서도 지금 동작하지 않으므로
     * 필수로 조여도 되돌아갈 동작이 없다.
     *
     * <p>기본값을 자동으로 채우지 않는 이유는 그대로다: 하이라이트가 전부 "투어 자랑"은 아니다.
     */
    @NotNull(message = "카테고리를 골라주세요.")
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
