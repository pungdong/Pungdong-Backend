package com.diving.pungdong.enrollment.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 강사 제안 슬롯 선택 — {@code POST /enrollments/rounds/{roundId}/pick-slot}. 강사가 이미 이용권·블록까지 정해
 * 제안 = 사전 수락이라, 고르면 그 슬롯으로 reschedule + 바로 PAYMENT_PENDING(입장료는 그 daypart 로 재산정).
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
