package com.diving.pungdong.enrollment;

import lombok.*;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalTime;

/**
 * 회차 슬롯 변경 이력 1줄 — 일정 수정(reschedule)·제안 슬롯 선택(pick-slot) 시 <b>변경 전</b> 슬롯을 박제한다.
 * "취소"가 아니라 같은 회차의 일정이 바뀐 것이므로 회차는 유지하고 옛 슬롯만 여기 쌓는다(CS 추적용).
 */
@Embeddable
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PastSlot {

    @Column(name = "past_date")
    private LocalDate date;
    @Column(name = "past_venue_ref")
    private String venueRefId;
    @Column(name = "past_ticket_ref")
    private String ticketRef;
    @Column(name = "past_block_start")
    private LocalTime blockStart;
    @Column(name = "past_block_end")
    private LocalTime blockEnd;
    @Column(name = "changed_at")
    private OffsetDateTime changedAt;
}
