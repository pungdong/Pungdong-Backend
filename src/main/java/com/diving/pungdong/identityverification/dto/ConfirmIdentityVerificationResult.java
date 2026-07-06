package com.diving.pungdong.identityverification.dto;

import com.diving.pungdong.identityverification.IdentityVerificationErrorCode;
import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import lombok.*;

/**
 * OTP 확인 결과 (200). 성공/실패 모두 <b>200</b> — OTP 재입력은 정상 UI 분기이므로(repo 규약).
 *
 * <ul>
 *   <li>{@code status=VERIFIED} → {@code realName} 세팅, {@code errorCode} null. 이 verificationId 를
 *       강사 신청/결제에 재사용.</li>
 *   <li>{@code status=FAILED} → {@code errorCode}(OTP_MISMATCH/OTP_EXPIRED/OTP_TOO_MANY_ATTEMPTS)로
 *       FE 가 문구 매핑. (문자 발송 실패 SMS_SEND_FAILED 는 여기가 아니라 발송 API 의 400.)</li>
 * </ul>
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ConfirmIdentityVerificationResult {
    private Long verificationId;
    private IdentityVerificationStatus status;
    private String realName;                        // VERIFIED 시
    private IdentityVerificationErrorCode errorCode; // FAILED 시
}
