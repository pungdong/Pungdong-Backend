package com.diving.pungdong.enrollment.dto;

import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 강사가 받은 신청 1건(검토용) — V2 enrollment-management 검토 시트의 BE 데이터. 목록은
 * {@code _embedded.enrollments}. 학생 식별·일정·장비·추정 금액을 강사 측에서 본다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "enrollments")
public class InstructorEnrollmentResponse {

    private Long id;
    private Long studentId;
    private String studentName;
    private Long courseId;
    private String courseTitle;

    private LocalDate date;
    private LocalTime blockStart;
    private LocalTime blockEnd;
    private String venueRefId;
    private String venueName;

    private EnrollmentStatus status;
    private int total;
    private List<EnrollmentResponse.EquipmentLine> equipment;

    private LocalDateTime createdAt;

    public static InstructorEnrollmentResponse of(Enrollment e, String venueName) {
        return InstructorEnrollmentResponse.builder()
                .id(e.getId())
                .studentId(e.getStudent() == null ? null : e.getStudent().getId())
                .studentName(e.getStudent() == null ? null : e.getStudent().getNickName())
                .courseId(e.getCourse() == null ? null : e.getCourse().getId())
                .courseTitle(e.getCourse() == null ? null : e.getCourse().getTitle())
                .date(e.getDate())
                .blockStart(e.getBlockStart())
                .blockEnd(e.getBlockEnd())
                .venueRefId(e.getVenueRefId())
                .venueName(venueName)
                .status(e.getStatus())
                .total(e.estimatedTotal())
                .equipment(e.getEquipment().stream()
                        .map(EnrollmentResponse.EquipmentLine::from).collect(Collectors.toList()))
                .createdAt(e.getCreatedAt())
                .build();
    }
}
