package com.diving.pungdong.certificate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

/** NEW 검수 행 상세의 "신청" 블록 — instructorapplication 이 포트로 채워 준다(PII 포함, ADMIN 전용). */
@Getter
@Builder
@AllArgsConstructor
public class ApplicationReviewView {
    private final Long applicationId;
    /** SUBMITTED | APPROVED | REJECTED */
    private final String status;
    private final List<Long> certificateIds;
    private final String insuranceFileKey;
    private final String insuranceViewUrl;
    private final String realName;
    private final String birth;
    private final String phoneNumber;
    private final String rejectionReason;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime submittedAt;
    private final OffsetDateTime reviewedAt;
    private final String reviewerNickName;
}
