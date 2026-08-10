package com.diving.pungdong.branding.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;

/**
 * 게시물 숨기기 토글 — {@code PATCH /branding/me/posts/{id}/visibility}.
 * <b>삭제와 다르다</b> — 되돌릴 수 있고, 공개 경로에서만 빠진다.
 */
@Getter @Setter
@NoArgsConstructor
public class PostVisibilityRequest {

    @NotNull(message = "숨김 여부를 지정해주세요.")
    private Boolean hidden;
}
