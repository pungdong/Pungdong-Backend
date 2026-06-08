package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

/** 본인확인 결과 — 신청 제출 시 이 verificationId 를 참조한다. */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class IdentityVerificationResult {
    private Long verificationId;
    private boolean verified;
    private String realName;
}
