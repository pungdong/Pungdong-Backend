package com.diving.pungdong.availability.dto;

import com.diving.pungdong.availability.RecurrenceMode;
import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 가용시간 추가 요청 — V2 "가용시간 추가" 시트. {@code instructor} 는 바디가 아니라 컨트롤러가 현재 계정으로
 * 주입. 반복(recurrence) 전개·시간/정원 검증은 {@code AvailabilityService} 에서(조건부 규칙이라 bean
 * validation 으로 다 못 잡음).
 *
 * <ul>
 *   <li>{@link RecurrenceMode#ONCE} — {@code date} 하루.</li>
 *   <li>{@link RecurrenceMode#WEEKLY}/{@link RecurrenceMode#FOUR_WEEKS} — {@code dayOfWeeks} 요일들을
 *       1주/4주에 걸쳐 전개({@code date} 가 속한 주부터, 과거일 제외).</li>
 * </ul>
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AvailabilityCreateRequest {

    @NotNull
    private RecurrenceMode mode;

    /** 기준 날짜(ONCE = 그 하루 / WEEKLY·FOUR_WEEKS = 전개 시작이 속한 주). */
    @NotNull
    private LocalDate date;

    /** WEEKLY/FOUR_WEEKS 에서 열 요일들(폼의 월~일 체크칩). ONCE 면 무시. */
    private List<DayOfWeek> dayOfWeeks;

    @NotNull
    private LocalTime startTime;

    @NotNull
    private LocalTime endTime;

    /**
     * 정원 override(선택). <b>비우면(null) 계정 기본 정원을 따른다</b>(권장 — sparse override). 값을 주면
     * 그 일정만 그 인원으로 고정. 주면 1 이상({@code AvailabilityService} 검증).
     */
    private Integer capacity;

    /** 위치 토큰(선택) — "CUSTOM:&lt;pk&gt;"/"OFFICIAL:&lt;sanityId&gt;". 빈 가용시간이면 비움. */
    private String venueRefId;

    /** 세션 라벨(선택) — "1부"/"오후". */
    private String sessionLabel;
}
