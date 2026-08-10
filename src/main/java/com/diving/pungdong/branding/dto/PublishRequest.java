package com.diving.pungdong.branding.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;

/**
 * 발행 토글 — {@code PATCH /branding/me/publish}.
 *
 * <p>승인(APPROVED) 게이트가 <b>없다</b>(D2) — 일반 유저도 발행할 수 있다. 인증마크만 승인된 강사에게
 * 붙는다.
 */
@Getter @Setter
@NoArgsConstructor
public class PublishRequest {

    @NotNull(message = "공개 여부를 지정해주세요.")
    private Boolean published;
}
