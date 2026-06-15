package com.diving.pungdong.enrollment;

import lombok.*;

import javax.persistence.*;

/**
 * 신청에 포함된 대여 장비 1건 — 신청 시점 스냅샷(이름·가격). 장비 가격표(venue.equipment)는 강사가 나중에
 * 바꿀 수 있으므로 신청 시점 값을 박아둔다(영수증·정산 기준).
 */
@Entity
@Table(name = "enrollment_equipment")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EnrollmentEquipment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private Enrollment enrollment;

    /** 장비 가격표 아이템 식별자(VenueEquipmentResponse.Item.id 문자열). */
    private String itemRef;

    /** 신청 시점 이름 스냅샷. */
    private String name;

    /** 신청 시점 대여가 스냅샷(원). 포함(무료) 장비는 0. */
    private int priceSnapshot;
}
