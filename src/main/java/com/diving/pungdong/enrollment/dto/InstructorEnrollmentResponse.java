package com.diving.pungdong.enrollment.dto;

import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 강사가 받은 회차 1건(검토용) — V2 enrollment-management 검토 시트의 BE 데이터. {@code id} = 회차 id. 목록은
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
    private Integer roundIndex;

    private LocalDate date;
    private LocalTime blockStart;
    private LocalTime blockEnd;
    private String venueRefId;
    private String venueName;

    private EnrollmentStatus status;
    private int total;
    private List<EnrollmentResponse.EquipmentLine> equipment;

    private OffsetDateTime createdAt;

    public static InstructorEnrollmentResponse of(EnrollmentRound r, String venueName) {
        var enrollment = r.getEnrollment();
        var student = enrollment == null ? null : enrollment.getStudent();
        var course = enrollment == null ? null : enrollment.getCourse();
        return InstructorEnrollmentResponse.builder()
                .id(r.getId())
                .studentId(student == null ? null : student.getId())
                .studentName(student == null ? null : student.getNickName())
                .courseId(course == null ? null : course.getId())
                .courseTitle(course == null ? null : course.getTitle())
                .roundIndex(r.getRoundIndex())
                .date(r.getDate())
                .blockStart(r.getBlockStart())
                .blockEnd(r.getBlockEnd())
                .venueRefId(r.getVenueRefId())
                .venueName(venueName)
                .status(r.getStatus())
                .total(r.chargeTotal())
                .equipment(r.getEquipment().stream()
                        .map(EnrollmentResponse.EquipmentLine::from).collect(Collectors.toList()))
                .createdAt(r.getCreatedAt())
                .build();
    }
}
