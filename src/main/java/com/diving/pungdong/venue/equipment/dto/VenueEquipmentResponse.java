package com.diving.pungdong.venue.equipment.dto;

import com.diving.pungdong.venue.equipment.SizeFormat;
import com.diving.pungdong.venue.equipment.VenueEquipmentItem;
import com.diving.pungdong.venue.equipment.VenueEquipmentExtension;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 한 위치의 대여 장비 가격표(equipment extension) 응답. 목록({@code GET /venue-equipment})의
 * CollectionModel 키 = "extensions".
 *
 * <p>{@code source} — MINE(강사 저장분, 기존 동작) | VENUE_DEFAULT(저장분 없음 → OFFICIAL venue 의
 * 기본 장비를 prefill 로 합성). VENUE_DEFAULT 일 때 {@code id}/item {@code id} 는 null(예약 불가 —
 * Step 3 저장(PUT) 시 실체화되며 id 부여), {@code sizeOptions} 는 null(= "자동" — [] 로 주면 FE 가
 * "0개 선택"으로 렌더해 표시≠저장이 된다, 계약서 §3).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "extensions")
public class VenueEquipmentResponse {

    /** 가격표의 출처 — 강사 저장분인지, venue 기본 장비 prefill 합성인지. */
    public enum Source { MINE, VENUE_DEFAULT }

    private Long id;
    private String venueRefId;
    private List<Item> items;
    private Source source;

    public static VenueEquipmentResponse from(VenueEquipmentExtension p) {
        return VenueEquipmentResponse.builder()
                .id(p.getId())
                .venueRefId(p.getVenueRefId())
                .items(p.getItems().stream().map(Item::from).collect(Collectors.toList()))
                .source(Source.MINE)
                .build();
    }

    @Getter @Setter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class Item {
        private Long id;
        private String name;
        private int price;
        private SizeFormat sizeFormat;
        private List<String> sizeOptions;

        static Item from(VenueEquipmentItem i) {
            return Item.builder()
                    .id(i.getId())
                    .name(i.getName())
                    .price(i.getPrice())
                    .sizeFormat(i.getSizeFormat())
                    .sizeOptions(i.getSizeOptions())
                    .build();
        }
    }
}
