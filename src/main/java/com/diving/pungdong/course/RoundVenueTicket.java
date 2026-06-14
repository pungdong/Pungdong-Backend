package com.diving.pungdong.course;

import com.diving.pungdong.venue.DaypartKind;
import lombok.*;

import javax.persistence.*;

/**
 * 강사가 그 위치에서 허용한 이용권 변형 1건 = (이용권 ref × 평일/주말). {@code ticketRef} 는 코스 빌더
 * 위치 응답의 이용권 식별자(custom=티켓 id, official=이름 등) — BE 는 선택을 그대로 보관하고, 실제
 * 가격/시간 해석은 부킹 시점(reservation, 후속). 그래서 PR4 는 ticketRef 깊은 검증은 하지 않는다.
 */
@Entity
@Table(name = "round_venue_ticket")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RoundVenueTicket {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_venue_id")
    private RoundVenue roundVenue;

    /** 위치 내 이용권 식별자(코스 빌더 응답이 준 값). */
    private String ticketRef;

    @Enumerated(EnumType.STRING)
    private DaypartKind daypart;

    private int sortOrder;
}
