package com.diving.pungdong.availability.dto;

import lombok.*;

import java.time.LocalTime;

/**
 * 하루 안의 시간 구간 하나 — {@link CoverageRequest#getTimeRanges()} 의 원소.
 *
 * <p>점심을 비우고 오전·오후를 따로 여는 케이스(10–12, 13–18)를 <b>한 요청·한 트랜잭션</b>으로 받기 위한 것.
 * 여러 번 POST 하면 중간에 실패했을 때 앞의 것만 반영되는 부분 실패가 생긴다.
 *
 * <p>겹치거나 맞닿는 구간을 그대로 보내도 된다 — {@code CoverageMerger} 가 합치므로 클라이언트가 미리
 * 병합할 필요가 없다. 끝시각은 {@code DayEnd.normalizeEnd} 로 정규화된 뒤 검증된다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class TimeRangeRequest {

    private LocalTime startTime;
    private LocalTime endTime;
}
