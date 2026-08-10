package com.diving.pungdong.venue.favorite.dto;

import lombok.*;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 즐겨찾기 마크 요청 — {@code POST /venue-favorites}. 신원(강사)은 세션에서 오므로 바디엔 위치만 담는다.
 *
 * <p>형식(shape)까지 검증하는 이유: {@code venueRefId} 는 곧바로 {@code VenueScope.parse} 로 쪼개져
 * DB 조회 키가 되므로, 형식이 어긋난 값은 side-effect 전에 컨트롤러 입구에서 걸러야 한다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class VenueFavoriteRequest {

    /** {@code "CUSTOM:<pk>"} | {@code "OFFICIAL:<sanityId>"} — 위치 목록이 준 그 토큰. */
    @NotBlank(message = "위치를 선택해주세요.")
    @Size(max = 255, message = "위치 식별자가 너무 깁니다.")
    @Pattern(regexp = "^(CUSTOM|OFFICIAL):.+$", message = "위치 식별자 형식이 올바르지 않습니다.")
    private String venueRefId;
}
