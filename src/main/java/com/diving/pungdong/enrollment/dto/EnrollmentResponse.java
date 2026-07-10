package com.diving.pungdong.enrollment.dto;

import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundEquipment;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 내 회차 응답(학생) — 신청 직후 / 내 회차 목록. {@code id} = 회차 id(취소·결제 등 행위 단위). 목록은
 * {@code _embedded.enrollments}. venueName 은 호출자가 {@code VenueRefResolver} 로 해석해 넘긴다(미해석 시 null).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "enrollments")
public class EnrollmentResponse {

    private Long id;
    private Long courseId;
    private String courseTitle;
    private String instructorName;
    private Integer roundIndex;

    private LocalDate date;
    private LocalTime blockStart;
    private LocalTime blockEnd;
    private String venueRefId;
    private String venueName;

    private EnrollmentStatus status;
    private String rejectionReason;

    /** 추정 금액(스냅샷). 수강료는 첫 만남 회차만, 부대비용은 회차별. 권위 금액은 결제 시점 재계산. */
    private int tuition;
    private int entry;
    private int equipmentTotal;
    private int total;
    private List<EquipmentLine> equipment;

    private OffsetDateTime createdAt;
    private OffsetDateTime respondedAt;

    /** 슬롯 변경 이력(일정 수정/제안 선택으로 슬롯이 바뀐 기록 — CS 추적). 변경 없으면 빈 배열. */
    private List<SlotHistoryLine> slotHistory;

    public static EnrollmentResponse of(EnrollmentRound r, String venueName, String instructorName) {
        var course = r.getEnrollment() == null ? null : r.getEnrollment().getCourse();
        return EnrollmentResponse.builder()
                .id(r.getId())
                .courseId(course == null ? null : course.getId())
                .courseTitle(course == null ? null : course.getTitle())
                .instructorName(instructorName)
                .roundIndex(r.getRoundIndex())
                .date(r.getDate())
                .blockStart(r.getBlockStart())
                .blockEnd(r.getBlockEnd())
                .venueRefId(r.getVenueRefId())
                .venueName(venueName)
                .status(r.getStatus())
                .rejectionReason(r.getRejectionReason())
                .tuition(r.isFirstMeeting() && r.getEnrollment() != null ? r.getEnrollment().getTuitionSnapshot() : 0)
                .entry(r.getEntrySnapshot())
                .equipmentTotal(r.getEquipmentSnapshot())
                .total(r.chargeTotal())
                .equipment(r.getEquipment().stream().map(EquipmentLine::from).collect(Collectors.toList()))
                .createdAt(r.getCreatedAt())
                .respondedAt(r.getRespondedAt())
                .slotHistory(r.getSlotHistory().stream().map(SlotHistoryLine::from).collect(Collectors.toList()))
                .build();
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SlotHistoryLine {
        private LocalDate date;
        private String venueRefId;
        private String ticketRef;
        private LocalTime blockStart;
        private LocalTime blockEnd;
        private OffsetDateTime changedAt;

        static SlotHistoryLine from(com.diving.pungdong.enrollment.PastSlot p) {
            return SlotHistoryLine.builder()
                    .date(p.getDate()).venueRefId(p.getVenueRefId()).ticketRef(p.getTicketRef())
                    .blockStart(p.getBlockStart()).blockEnd(p.getBlockEnd()).changedAt(p.getChangedAt()).build();
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EquipmentLine {
        private String itemRef;
        private String name;
        private int price;
        private String size;

        static EquipmentLine from(EnrollmentRoundEquipment x) {
            return EquipmentLine.builder()
                    .itemRef(x.getItemRef()).name(x.getName()).price(x.getPriceSnapshot()).size(x.getSize()).build();
        }
    }
}
