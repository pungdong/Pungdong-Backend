package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 강사 신청 1건 (종목별). {@code GET /instructor-applications/me} 는 이 항목의 <b>리스트</b>를
 * 돌려준다 — 신청한 종목마다 한 항목. 특정 종목 미신청은 리스트에 없음(= FE 가 "신청하기" 노출).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class MyInstructorApplicationResponse {

    private String disciplineCode;
    /** "SUBMITTED" | "APPROVED" | "REJECTED" */
    private String status;

    /** 자격증 목록 (단체+이미지). 자격증 불필요 종목은 빈 목록. */
    private List<ApplicationCertificateDto> certificates;
    private boolean identityVerified;
    private String rejectionReason;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
}
