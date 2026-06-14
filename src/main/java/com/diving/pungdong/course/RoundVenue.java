package com.diving.pungdong.course;

import lombok.*;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 회차의 진행 위치 1건 — {@code venueRefId}({@link com.diving.pungdong.venue.VenueScope} 토큰
 * "CUSTOM:<pk>"/"OFFICIAL:<sanityId>")로 코스 빌더 목록의 위치를 가리킨다. 선택한 이용권 변형
 * (이용권 × 평일/주말)을 {@link RoundVenueTicket} 로 담는다. 위치별 대여 장비는 저장하지 않고
 * (강사×위치 가격표에서 읽기 시점 합성).
 */
@Entity
@Table(name = "round_venue")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RoundVenue {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id")
    private CourseRound round;

    /** "CUSTOM:<pk>" | "OFFICIAL:<sanityId>". */
    private String venueRefId;

    private int sortOrder;

    @OneToMany(mappedBy = "roundVenue", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder asc, id asc")
    @Builder.Default
    private List<RoundVenueTicket> tickets = new ArrayList<>();

    public void addTicket(RoundVenueTicket t) {
        t.setRoundVenue(this);
        this.tickets.add(t);
    }
}
