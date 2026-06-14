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
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "extensions")
public class VenueEquipmentResponse {

    private Long id;
    private String venueRefId;
    private List<Item> items;

    public static VenueEquipmentResponse from(VenueEquipmentExtension p) {
        return VenueEquipmentResponse.builder()
                .id(p.getId())
                .venueRefId(p.getVenueRefId())
                .items(p.getItems().stream().map(Item::from).collect(Collectors.toList()))
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
