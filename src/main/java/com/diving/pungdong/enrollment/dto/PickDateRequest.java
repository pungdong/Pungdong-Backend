package com.diving.pungdong.enrollment.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 학생이 강사 제안 날짜 중 하나 선택 — {@code POST /enrollments/rounds/{roundId}/pick-date}. 강사가 이미 제안 =
 * 사전 수락이라, 고르면 그 날짜로 reschedule + 바로 PAYMENT_PENDING(결제 대기).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class PickDateRequest {
    @NotNull
    private LocalDate date;
}
