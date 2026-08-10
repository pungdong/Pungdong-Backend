package com.diving.pungdong.venue.favorite.dto;

import com.diving.pungdong.venue.favorite.VenueFavorite;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;

/** 즐겨찾기 1건. 목록 키 = {@code venueFavorites}. 표식이라 페이로드는 위치 토큰뿐. */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "venueFavorites")
public class VenueFavoriteResponse {

    /** {@code "CUSTOM:<pk>"} | {@code "OFFICIAL:<sanityId>"}. */
    private String venueRefId;
    private OffsetDateTime createdAt;

    public static VenueFavoriteResponse from(VenueFavorite f) {
        return VenueFavoriteResponse.builder()
                .venueRefId(f.getVenueRefId())
                .createdAt(f.getCreatedAt())
                .build();
    }
}
