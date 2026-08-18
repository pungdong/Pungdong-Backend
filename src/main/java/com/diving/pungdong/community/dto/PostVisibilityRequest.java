package com.diving.pungdong.community.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;

/**
 * 게시물 숨기기 — {@code PATCH /community/posts/{id}/visibility}.
 *
 * <p><b>삭제와 다르다</b> — 되돌릴 수 있고, 공개 경로에서만 빠진다(오너에게는 남는다).
 *
 * <p>⚠️ <b>"토글" 이라고 부르지 않는다 — wire 는 명시적 값이다.</b> 서버가 현재 상태를 뒤집는 게 아니라
 * 클라이언트가 보낸 {@code hidden} 을 그대로 반영한다. 그래서 <b>이미 숨긴 글에 {@code hidden:true} 를
 * 보내면 no-op</b> 이고, 상태를 모르는 화면이 눌러도 의도치 않게 공개되지 않는다.
 * (UI 는 토글로 그리는 게 맞지만 그 단어를 여기 쓰면 <b>멱등하지 않다는 인상</b>을 준다 —
 * 실제로 "상태를 모른 채 누르면 공개돼버리나" 라는 오판을 한 번 유발했다.)
 * 형제 DTO {@code PublishRequest}·{@code PostPinRequest} 도 같은 모양이다.
 *
 * <p><b>숨김은 전역 스위치다.</b> 컬럼이 하나({@code branding_post.is_hidden})라 어느 화면에서 켜든
 * 커뮤니티 피드와 브랜딩 그리드 양쪽에서 빠지고, 어디서 풀든 양쪽이 함께 돌아온다.
 * 그래서 <b>경로도 하나</b>다 — 브랜딩 쪽 쌍둥이 엔드포인트({@code PATCH /branding/me/posts/{id}/visibility})는
 * 삭제했다: 프로필 글에만 동작해 커뮤니티 전용 글을 못 숨겼고, 무엇보다 어드민 조치(ACTIONED) 확인을
 * 하지 않아 <b>신고로 내려간 글을 작성자가 되살리는 우회로</b>였다(community 는 그걸 막는다).
 *
 * <p>이 DTO 가 branding 이 아니라 community 패키지에 있는 이유도 그것 — 이제 커뮤니티만 쓴다.
 */
@Getter @Setter
@NoArgsConstructor
public class PostVisibilityRequest {

    @NotNull(message = "숨김 여부를 지정해주세요.")
    private Boolean hidden;
}
