package com.diving.pungdong.availability.dto;

import com.diving.pungdong.availability.RecurrenceMode;
import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 예약가능시간(coverage) 직접 편집 — 열기(POST, union)/닫기(DELETE, subtract). 항상 머지·정규화된다.
 *
 * <ul>
 *   <li>열기: {@code mode} 로 ONCE/WEEKLY/FOUR_WEEKS 전개(여러 날에 같은 시간대 개방). 각 날 union+머지.</li>
 *   <li>닫기: 단일 {@code date} + 시간만 사용(반복 무시). 그 구간에 일정(session)이 걸치면 거부
 *       ({@code COVERAGE_HAS_SESSION}).</li>
 * </ul>
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CoverageRequest {

    /** 열기 전개 모드(닫기는 무시). null = ONCE. */
    private RecurrenceMode mode;

    @NotNull
    private LocalDate date;

    /** WEEKLY/FOUR_WEEKS 에서 열 요일들(ONCE/닫기면 무시). */
    private List<DayOfWeek> dayOfWeeks;

    @NotNull
    private LocalTime startTime;
    @NotNull
    private LocalTime endTime;
}
