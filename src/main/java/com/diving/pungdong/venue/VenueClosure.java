package com.diving.pungdong.venue;

import lombok.*;

import javax.persistence.*;
import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 정기 휴무 규칙 1개 (위치 공통 · 권종 무관). 한 위치에 여러 규칙(매주 + 월간 동시) 가능.
 *
 * <ul>
 *   <li>{@code WEEKLY} — {@code weekdays} 사용 (매주 X·Y요일).</li>
 *   <li>{@code MONTHLY} — {@code nths}(1~5, 다중) + {@code monthlyWeekday}(1개) 사용 (매월 N째 주 X요일).</li>
 * </ul>
 *
 * <p>요일은 {@link DayOfWeek} 로 저장한다 — 디자인의 한글 글자(월·화…)는 표현용일 뿐.
 */
@Entity
@Table(name = "venue_closure")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class VenueClosure {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    @Enumerated(EnumType.STRING)
    private ClosureType type;

    /** WEEKLY: 매주 휴무 요일들. */
    @ElementCollection(targetClass = DayOfWeek.class)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "venue_closure_weekday", joinColumns = @JoinColumn(name = "closure_id"))
    @Column(name = "weekday")
    @Builder.Default
    private Set<DayOfWeek> weekdays = new LinkedHashSet<>();

    /** MONTHLY: 몇째 주 (1~5, 다중). */
    @ElementCollection
    @CollectionTable(name = "venue_closure_nth", joinColumns = @JoinColumn(name = "closure_id"))
    @Column(name = "nth")
    @Builder.Default
    private Set<Integer> nths = new LinkedHashSet<>();

    /** MONTHLY: 요일 1개. */
    @Enumerated(EnumType.STRING)
    private DayOfWeek monthlyWeekday;
}
