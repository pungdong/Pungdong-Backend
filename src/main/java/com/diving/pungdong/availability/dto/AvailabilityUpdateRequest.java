package com.diving.pungdong.availability.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 가용시간 window 1건 수정 — 날짜·시간·위치·세션. 반복(recurrence)은 생성 전용이라 여기 없다(window 하나만
 * 고친다). <b>정원은 여기서 안 다룬다</b> — 계정 기본값(PATCH {@code /settings}) 또는 일정 override
 * (PATCH/DELETE {@code /{id}/capacity})로 분리. 두 관심사를 섞지 않아 "시간 고치다 정원이 핀 박히는" 사고 방지.
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

    private String venueRefId;

    private String sessionLabel;
}
