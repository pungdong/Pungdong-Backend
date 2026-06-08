package com.diving.pungdong.instructorapplication.dto;

import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.*;

import java.time.LocalDateTime;

/** 어드민 대기 목록의 한 행 (PII 최소화 — 상세는 detail 에서). */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class InstructorApplicationSummary {
    private Long applicationId;
    private Long accountId;
    private String nickName;
    private String organizationCode;
    private String organizationOther;
    private InstructorApplicationStatus status;
    private LocalDateTime submittedAt;
}
