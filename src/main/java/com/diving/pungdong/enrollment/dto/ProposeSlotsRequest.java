package com.diving.pungdong.enrollment.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 강사 일정변경요청 — {@code POST /instructor/enrollments/{roundId}/propose-slots}. 위치는 회차에 고정,
 * <b>완전한 대안 슬롯(날짜+이용권+블록)</b>을 제안한다(서버가 각 슬롯 bookable 검증). 학생이 고르면 사전 수락.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ProposeSlotsRequest {

    private List<SlotProposal> slots;

    @Getter @Setter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class SlotProposal {
        private LocalDate date;
        private String ticketRef;
        private LocalTime blockStart;
        private LocalTime blockEnd;
    }
}
