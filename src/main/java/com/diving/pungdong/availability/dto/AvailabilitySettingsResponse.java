package com.diving.pungdong.availability.dto;

import lombok.*;

/**
 * 강사 스케줄 설정 — 현재는 기본 정원 하나. GET/PATCH {@code /instructor/availability/settings} 응답.
 * 일정탭 상단 ± 가 읽고 쓰는 baseline. 앞으로 기본 세션길이·기본 위치 등이 늘면 이 응답에 필드를 더한다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AvailabilitySettingsResponse {

    /** 강사 기본 수용 인원 — override 없는 일정들의 유효정원. */
    private int defaultCapacity;
}
