package com.diving.pungdong.branding.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;

/**
 * 게시물 숨기기 — {@code PATCH /branding/me/posts/{id}/visibility}
 * (커뮤니티도 같은 DTO 를 쓴다: {@code PATCH /community/posts/{id}/visibility}).
 *
 * <p><b>삭제와 다르다</b> — 되돌릴 수 있고, 공개 경로에서만 빠진다.
 *
 * <p>⚠️ <b>"토글" 이라고 부르지 않는다 — wire 는 명시적 값이다.</b> 서버가 현재 상태를 뒤집는 게 아니라
 * 클라이언트가 보낸 {@code hidden} 을 그대로 반영한다. 그래서 <b>이미 숨긴 글에 {@code hidden:true} 를
 * 보내면 no-op</b> 이고, 상태를 모르는 화면이 눌러도 의도치 않게 공개되지 않는다.
 * (UI 는 토글로 그리는 게 맞지만 그 단어를 여기 쓰면 <b>멱등하지 않다는 인상</b>을 준다 —
 * 실제로 "상태를 모른 채 누르면 공개돼버리나" 라는 오판을 한 번 유발했다.)
 * 형제 DTO {@code PublishRequest}·{@code PostPinRequest} 도 같은 모양이다.
 */
@Getter @Setter
@NoArgsConstructor
public class PostVisibilityRequest {

    @NotNull(message = "숨김 여부를 지정해주세요.")
    private Boolean hidden;
}
