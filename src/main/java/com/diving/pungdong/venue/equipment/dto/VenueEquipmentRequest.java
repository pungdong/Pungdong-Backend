package com.diving.pungdong.venue.equipment.dto;

import com.diving.pungdong.venue.equipment.SizeFormat;
import lombok.*;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.util.ArrayList;
import java.util.List;

/**
 * 강사가 한 위치의 대여 장비 가격표를 저장(upsert)하는 요청 — {@code PUT /venue-equipment}.
 * {@code venueRefId} 는 코스 빌더 목록({@code GET /venues/builder})이 준 토큰 그대로.
 * 자식(items)은 전량 교체 스냅샷.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class VenueEquipmentRequest {

    /** "CUSTOM:<pk>" | "OFFICIAL:<sanityId>". */
    @NotNull
    private String venueRefId;

    @Valid
    @NotNull
    @Builder.Default
    private List<Item> items = new ArrayList<>();

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Item {
        @NotNull
        private String name;
        @PositiveOrZero
        private int price;
        /** null 이면 NONE 으로 취급. */
        private SizeFormat sizeFormat;
        /** 비우면 sizeFormat 프리셋으로 채움(SHOE_MM/APPAREL_SXL). CUSTOM 은 준 값 그대로. */
        @Builder.Default
        private List<String> sizeOptions = new ArrayList<>();
    }
}
