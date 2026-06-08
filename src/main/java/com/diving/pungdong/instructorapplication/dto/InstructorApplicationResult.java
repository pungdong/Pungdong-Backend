package com.diving.pungdong.instructorapplication.dto;

import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.*;

/** 신청 제출/재제출 결과. */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class InstructorApplicationResult {
    private Long applicationId;
    private InstructorApplicationStatus status;
}
