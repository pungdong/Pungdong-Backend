package com.diving.pungdong.identityverification.dto;

import lombok.*;

import javax.validation.constraints.NotBlank;

/** OTP 확인 요청 — {@code POST /identity-verifications/{id}/confirm}. */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ConfirmIdentityVerificationRequest {

    @NotBlank
    private String otp;
}
