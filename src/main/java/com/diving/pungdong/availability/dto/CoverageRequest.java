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
 *   <li>열기: {@code mode} 로 ONCE/WEEKLY/FOUR_WEEKS/MONTH 전개(여러 날에 같은 시간대 개방). 각 날 union+머지.
 *       시간은 {@code timeRanges} 여러 벌 또는 {@code startTime}/{@code endTime} 한 벌.</li>
 *   <li>닫기: 단일 {@code date} + {@code startTime}/{@code endTime} 만 사용(반복·{@code timeRanges} 무시).
 *       그 구간에 일정(session)이 걸치면 거부({@code COVERAGE_HAS_SESSION}).</li>
 * </ul>
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CoverageRequest {

    /** 열기 전개 모드(닫기는 무시). null = ONCE. */
    private RecurrenceMode mode;

    /**
     * 반복 모드면 <b>"어느 기간"</b>(그 날이 속한 주/달), ONCE·닫기면 <b>"그 날"</b>.
     *
     * <p>반복 모드에서 전개 시작점은 이 값이 아니라 {@code max(오늘, 기간 시작)} 이다 — 달·주 중간 날을
     * 보내도 기간 앞부분이 빠지지 않는다. {@link RecurrenceMode} 참고.
     */
    @NotNull
    private LocalDate date;

    /** WEEKLY/FOUR_WEEKS/MONTH 에서 열 요일들(ONCE/닫기면 무시). */
    private List<DayOfWeek> dayOfWeeks;

    /**
     * 열 시간 구간들(선택) — 비어있지 않으면 <b>이것만</b> 쓰고 {@code startTime}/{@code endTime} 은 무시한다.
     * null/빈 배열이면 {@code startTime}/{@code endTime} 한 벌로 폴백. 최대 {@code MAX_TIME_RANGES} 개.
     *
     * <p>클라이언트는 첫 구간을 {@code startTime}/{@code endTime} 에도 겹쳐 보낸다 — 그래서 이 필드를 모르는
     * 구 서버에 닿아도 "조용히 틀림" 이 아니라 <b>"첫 구간만 열림"</b> 으로 degrade 한다. 닫기(DELETE)는
     * 이 필드를 보지 않는다.
     */
    private List<TimeRangeRequest> timeRanges;

    @NotNull
    private LocalTime startTime;
    @NotNull
    private LocalTime endTime;
}
