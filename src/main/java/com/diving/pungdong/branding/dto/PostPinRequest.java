package com.diving.pungdong.branding.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;

/** 게시물 고정 토글 — {@code PATCH /branding/me/posts/{id}/pin}. 고정된 글이 그리드 상단에 온다. */
@Getter @Setter
@NoArgsConstructor
public class PostPinRequest {

    @NotNull(message = "고정 여부를 지정해주세요.")
    private Boolean pinned;
}
