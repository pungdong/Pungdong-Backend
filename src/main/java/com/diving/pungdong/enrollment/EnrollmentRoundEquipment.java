package com.diving.pungdong.enrollment;

import lombok.*;

import javax.persistence.*;

/**
 * 회차별 대여 장비 명세 1줄 — 회차마다 빌리는 게 달라질 수 있어(본인 구매로 축소·딥풀 무료대여 등) 회차에 종속.
 * 신청 시점 가격을 박제(가격 변동 무관). {@link #size} 는 선택 사이즈(SHOE_MM/APPAREL_SXL), NONE 형식이면 null —
 * 신청 시 그 item 의 sizeOptions 멤버십을 서버가 검증한다(자유입력 차단).
 */
@Entity
@Table(name = "enrollment_round_equipment")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EnrollmentRoundEquipment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_round_id")
    private EnrollmentRound enrollmentRound;

    /** 장비 식별자(VenueEquipmentItem.id 문자열, 스냅샷). */
    private String itemRef;
    /** 이름 스냅샷. */
    private String name;
    /** 1인당 대여료 스냅샷(원, 0 = 무료). */
    private int priceSnapshot;
    /** 선택 사이즈("270"/"L" 등). NONE 형식이면 null. */
    private String size;
}
