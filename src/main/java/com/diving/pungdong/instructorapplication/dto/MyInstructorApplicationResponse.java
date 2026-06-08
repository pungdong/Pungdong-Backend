package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 강사 신청 상태 — 프로필 탭의 "강사 신청하기" 버튼 상태 + 신청완료 타임라인을 그리는 단일 소스.
 *
 * <p>신청 이력이 없으면 {@code status = "NONE"} 으로 200 응답한다 (404 아님 — 정상 UI 상태이지
 * 에러가 아니라는 repo API 규칙).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class MyInstructorApplicationResponse {

    /** "NONE" | "SUBMITTED" | "APPROVED" | "REJECTED" */
    private String status;

    private String organizationCode;
    private String organizationOther;
    private List<String> certificateImageUrls;
    private boolean identityVerified;
    private String rejectionReason;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;

    public static MyInstructorApplicationResponse none() {
        return MyInstructorApplicationResponse.builder().status("NONE").build();
    }
}
