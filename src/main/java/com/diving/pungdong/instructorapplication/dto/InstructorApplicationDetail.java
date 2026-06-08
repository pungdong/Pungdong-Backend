package com.diving.pungdong.instructorapplication.dto;

import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 어드민용 신청 상세 — 본인확인 PII 포함. ADMIN 권한 게이트 뒤에서만 노출된다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class InstructorApplicationDetail {
    private Long applicationId;
    private Long accountId;
    private String email;
    private String nickName;
    private InstructorApplicationStatus status;

    private String organizationCode;
    private String organizationOther;
    private List<String> certificateImageUrls;

    // 본인확인 결과 (PII)
    private String realName;
    private String birth;
    private String phoneNumber;

    private String rejectionReason;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
}
