package com.diving.pungdong.enrollment.dto;

import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentEquipment;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 내 신청 응답(학생) — 신청 직후 / 내 신청 목록. 목록은 {@code _embedded.enrollments}.
 * venueName 은 호출자가 {@code VenueRefResolver} 로 해석해 넘긴다(미해석 시 null).
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
    private int roundIndex;

    private LocalDate date;
    private LocalTime blockStart;
    private LocalTime blockEnd;
    private String venueRefId;
    private String venueName;

    private EnrollmentStatus status;
    private String rejectionReason;

    /** 추정 금액(신청 시점 스냅샷). 권위 금액은 확정/결제 시점(후속). */
    private int tuition;
    private int entry;
    private int equipmentTotal;
    private int total;
    private List<EquipmentLine> equipment;

    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;

    public static EnrollmentResponse of(Enrollment e, String venueName, String instructorName) {
        return EnrollmentResponse.builder()
                .id(e.getId())
                .courseId(e.getCourse() == null ? null : e.getCourse().getId())
                .courseTitle(e.getCourse() == null ? null : e.getCourse().getTitle())
                .instructorName(instructorName)
                .roundIndex(e.getRoundIndex())
                .date(e.getAvailabilityWindow() == null ? null : e.getAvailabilityWindow().getDate())
                .blockStart(e.getBlockStart())
                .blockEnd(e.getBlockEnd())
                .venueRefId(e.getVenueRefId())
                .venueName(venueName)
                .status(e.getStatus())
                .rejectionReason(e.getRejectionReason())
                .tuition(e.getTuitionSnapshot())
                .entry(e.getEntrySnapshot())
                .equipmentTotal(e.getEquipmentSnapshot())
                .total(e.estimatedTotal())
                .equipment(e.getEquipment().stream().map(EquipmentLine::from).collect(Collectors.toList()))
                .createdAt(e.getCreatedAt())
                .respondedAt(e.getRespondedAt())
                .build();
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EquipmentLine {
        private String itemRef;
        private String name;
        private int price;

        static EquipmentLine from(EnrollmentEquipment x) {
            return EquipmentLine.builder()
                    .itemRef(x.getItemRef()).name(x.getName()).price(x.getPriceSnapshot()).build();
        }
    }
}
