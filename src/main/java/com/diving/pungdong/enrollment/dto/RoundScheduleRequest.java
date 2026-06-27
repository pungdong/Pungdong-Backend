package com.diving.pungdong.enrollment.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 2회차+ 일정 신청 요청 — {@code POST /enrollments/{enrollmentId}/rounds}. courseId 는 enrollment 가 결정하므로
 * 슬롯만 받는다(어느 회차인지는 서버가 nextSchedulableRound 로 판정). 옵션 API({@code /next-options})가 준 슬롯 echo.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RoundScheduleRequest implements RoundSlotInput {

    @NotNull
    private LocalDate date;
    @NotNull
    private String venueRefId;
    @NotNull
    private String ticketRef;
    @NotNull
    private LocalTime blockStart;
    @NotNull
    private LocalTime blockEnd;

    /** 선택한 대여 장비 식별자. 비면 장비 없음. */
    private List<String> equipmentRefs;
}
