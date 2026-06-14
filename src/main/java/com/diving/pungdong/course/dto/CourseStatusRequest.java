package com.diving.pungdong.course.dto;

import com.diving.pungdong.course.CourseStatus;
import lombok.*;

import javax.validation.constraints.NotNull;

/** PATCH /courses/{id}/status 요청. */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CourseStatusRequest {
    @NotNull
    private CourseStatus status;
}
