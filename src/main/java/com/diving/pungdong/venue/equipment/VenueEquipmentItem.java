package com.diving.pungdong.venue.equipment;

import lombok.*;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 대여 장비 1종 — 이름 + 1인당 대여료(0 = 무료) + 사이즈 표기. 장비료는 평일/주말로 나뉘지 않고
 * 위치 종속(chat45). 사이즈는 {@link SizeFormat} + 옵션 목록(프리셋 자동 채움, 강사 override 가능).
 */
@Entity
@Table(name = "venue_equipment_item")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class VenueEquipmentItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "extension_id")
    private VenueEquipmentExtension extension;

    /** 예: 롱핀, 마스크·스노클, 슈트. */
    private String name;

    /** 1인당 대여료(원). 0 = 무료. */
    private int price;

    @Enumerated(EnumType.STRING)
    private SizeFormat sizeFormat;

    /** 수강생이 고를 사이즈 옵션. SHOE_MM/APPAREL_SXL 미입력 시 프리셋 채움, NONE 은 빈 목록. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "venue_equipment_item_size", joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "size_option")
    @OrderColumn(name = "size_order")
    @Builder.Default
    private List<String> sizeOptions = new ArrayList<>();

    private int sortOrder;
}
