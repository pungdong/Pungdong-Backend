package com.diving.pungdong.enrollment.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 강사 일정변경요청 — {@code POST /instructor/enrollments/{roundId}/propose-dates}. 같은 위치/이용권/블록으로
 * 가능한 대안 날짜들을 제안한다(서버가 각 날짜 bookable 검증). 학생이 그 중 하나를 고르면 사전 수락 → 결제 대기.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ProposeDatesRequest {
    private List<LocalDate> dates;
}
