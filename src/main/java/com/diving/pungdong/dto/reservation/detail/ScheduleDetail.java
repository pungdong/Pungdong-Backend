package com.diving.pungdong.dto.reservation.detail;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@AllArgsConstructor
@Builder
public class ScheduleDetail {
    private LocalTime startTime;

    private LocalTime endTime;

    private LocalDate date;
}
