package com.diving.pungdong.availability.dto;

import lombok.*;

import java.util.List;

/**
 * 강사 캘린더 범위 조회 응답 — 두 레이어 분리. {@code coverage} = 머지된 예약가능시간 구간[](위치/정원/사람
 * 없음), {@code sessions} = 그 위에 놓인 일정[](위치·정원·점유·신청자). FE 는 coverage 를 배경 띠로 깔고
 * sessions 를 카드로 얹는다. (GET /instructor/availability?from&to)
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AvailabilityCalendarResponse {

    private List<CoverageRangeResponse> coverage;
    private List<AvailabilitySessionResponse> sessions;
}
