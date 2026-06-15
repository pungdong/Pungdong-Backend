package com.diving.pungdong.availability.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 가용시간 window 1건 수정 — 날짜·시간·정원·위치·세션. 반복(recurrence)은 생성 전용이라 여기 없다(window
 * 하나만 고친다). 정원을 현재 점유보다 낮추면 거절(400) — {@code AvailabilityService} 에서 검증.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AvailabilityUpdateRequest {

    @NotNull
    private LocalDate date;

    @NotNull
    private LocalTime startTime;

    @NotNull
    private LocalTime endTime;

    private int capacity;

    private String venueRefId;

    private String sessionLabel;
}
