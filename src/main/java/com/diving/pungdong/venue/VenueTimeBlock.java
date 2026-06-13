package com.diving.pungdong.venue;

import lombok.*;

import javax.persistence.*;
import java.time.LocalTime;

/**
 * 고정 시간대(FIXED) 1구간 = "부" (예: 08:00–11:00). 수강생은 이 블록들 중 하나를 골라 신청한다.
 * 이용시간은 블록 길이에서 파생(저장 안 함).
 */
@Entity
@Table(name = "venue_time_block")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class VenueTimeBlock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daypart_id")
    private VenueDaypart daypart;

    private LocalTime startTime;
    private LocalTime endTime;
    private int sortOrder;
}
