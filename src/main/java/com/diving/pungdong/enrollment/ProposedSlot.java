package com.diving.pungdong.enrollment;

import lombok.*;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 강사 일정변경요청의 대안 슬롯 1개 — <b>완전한 슬롯</b>(날짜 + 이용권 + 시간블록). 위치는 회차에 고정이고
 * 날짜만 바꾸면 요일(평일/주말)이 바뀌며 daypart(이용권·입장료·블록)가 달라질 수 있어, 강사가 이용권·블록까지
 * 정해 제안해야 학생 선택이 곧 "사전 수락"이 된다(입장료는 선택 시점 그 daypart 로 재산정).
 */
@Embeddable
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProposedSlot {

    @Column(name = "proposed_date")
    private LocalDate date;
    @Column(name = "proposed_ticket_ref")
    private String ticketRef;
    @Column(name = "proposed_block_start")
    private LocalTime blockStart;
    @Column(name = "proposed_block_end")
    private LocalTime blockEnd;

    public boolean sameAs(LocalDate d, String ticket, LocalTime start, LocalTime end) {
        return java.util.Objects.equals(date, d)
                && java.util.Objects.equals(ticketRef, ticket)
                && java.util.Objects.equals(blockStart, start)
                && java.util.Objects.equals(blockEnd, end);
    }
}
