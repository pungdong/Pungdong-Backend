package com.diving.pungdong.availability.dto;

import lombok.*;

/**
 * 정원 값 1개 설정 — 두 ± 버튼이 공유한다:
 * <ul>
 *   <li>PATCH {@code /instructor/availability/settings} — 계정 기본 정원(baseline) 조정.</li>
 *   <li>PATCH {@code /instructor/availability/{id}/capacity} — 그 일정만 override 고정.</li>
 * </ul>
 * 1 이상({@code AvailabilityService} 검증). 일정 override 해제는 본문 없는 DELETE {@code /{id}/capacity}.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CapacityRequest {

    /** 새 정원 — 1 이상. */
    private Integer capacity;
}
