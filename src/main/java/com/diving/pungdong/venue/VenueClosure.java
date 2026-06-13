package com.diving.pungdong.venue;

import lombok.*;

import javax.persistence.*;
import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 정기 휴무 규칙 1개 (위치 공통 · 권종 무관). 한 위치에 여러 규칙 가능.
 *
 * <ul>
 *   <li>{@code WEEKLY} — {@code weekdays} 사용 (매주 X·Y요일).</li>
 *   <li>{@code MONTHLY} — <b>atomic</b>: {@code nth}(1~5) + {@code monthlyWeekday}(1개) = "매월 N째 주 X요일" 1건.
 *       "2·4주 수요일"이나 "2주 화 + 4주 목"은 MONTHLY 행을 여러 개 추가(grouping 은 UI 표현, 저장은 원자 단위).</li>
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

    /** MONTHLY: 몇째 주 (1~5, 1건). */
    private Integer nth;

    /** MONTHLY: 요일 1개. */
    @Enumerated(EnumType.STRING)
    private DayOfWeek monthlyWeekday;
}
