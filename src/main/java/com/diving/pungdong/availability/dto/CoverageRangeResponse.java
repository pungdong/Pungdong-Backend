package com.diving.pungdong.availability.dto;

import com.diving.pungdong.availability.AvailabilityCoverage;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 예약가능시간(coverage) 한 구간 — 순수 시간 띠. 위치/정원/사람 없음. 캘린더 응답에서 머지된 비겹침 구간[]로
 * 내려간다(FE 가 배경 "예약 가능" 띠로 렌더).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CoverageRangeResponse {

    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;

    public static CoverageRangeResponse of(AvailabilityCoverage c) {
        return CoverageRangeResponse.builder()
                .date(c.getDate())
                .startTime(c.getStartTime())
                .endTime(c.getEndTime())
                .build();
    }
}
