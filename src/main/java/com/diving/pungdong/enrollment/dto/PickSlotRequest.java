package com.diving.pungdong.enrollment.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 강사 제안 슬롯 선택 — {@code POST /enrollments/rounds/{roundId}/pick-slot}. 강사가 이미 이용권·블록까지 정해
 * 제안 = 강사가 승인한 자리라, 고르면 그 슬롯으로 reschedule + 추가 결제 없이 바로 CONFIRMED
 * (입장료는 그 daypart 로 재산정 — 싸졌으면 차액 자동환불).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class PickSlotRequest {
    @NotNull
    private LocalDate date;
    @NotNull
    private String ticketRef;
    @NotNull
    private LocalTime blockStart;
    @NotNull
    private LocalTime blockEnd;
}
