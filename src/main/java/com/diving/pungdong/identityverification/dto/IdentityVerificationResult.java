package com.diving.pungdong.identityverification.dto;

import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 본인확인 생성(=SMS 발송) 결과 (201). OTP 발송 직후라 {@code status=READY} — FE 는 이
 * {@code verificationId} 로 {@code POST /{id}/confirm} 을 호출한다. {@code otpExpiresAt} 은 OTP 유효기한
 * (재사용 만료 아님).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class IdentityVerificationResult {
    private Long verificationId;
    private IdentityVerificationStatus status;
    private LocalDateTime otpExpiresAt;
}
