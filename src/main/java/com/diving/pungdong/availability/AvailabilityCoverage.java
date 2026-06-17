package com.diving.pungdong.availability;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 예약가능시간(coverage) — <b>순수 시간 띠</b>. "이 범위 안에서 예약을 받을 수 있다"는 판정용 값일 뿐,
 * 위치·정원·세션·점유 아무것도 귀속하지 않는다. (강사 일정/점유는 {@link AvailabilitySession}.)
 *
 * <p><b>정규화 불변식</b>: 한 (instructor, date) 의 coverage row 들은 항상 <b>비겹침·비인접</b>으로 머지돼
 * 저장된다(10–12 + 12–14 → 10–14 한 줄). 머지는 {@link CoverageMerger} 가 write 시 수행 — row id 는 머지/분할로
 * 휘발성이라 다른 엔티티가 FK 로 참조하지 않는다(결합은 시간 포함 판정뿐).
 *
 * <p>불변식: 모든 {@link AvailabilitySession} 은 어떤 coverage 구간에 포함된다(원자적 일정추가가 보장,
 * coverage 축소는 session 가로지르면 거부).
 */
@Entity
@Table(name = "availability_coverage")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AvailabilityCoverage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id")
    private Account instructor;

    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;
}
