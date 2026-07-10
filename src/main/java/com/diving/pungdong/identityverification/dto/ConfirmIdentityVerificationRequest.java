package com.diving.pungdong.identityverification.dto;

import lombok.*;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * OTP 확인 요청 — {@code POST /identity-verifications/{id}/confirm}.
 *
 * <p>6자리 숫자만 통과시킨다. 구조적으로 정답일 수 없는 값(길이·문자)이 포트원 confirm 호출을 태우거나
 * {@code attemptCount}(5회 제한)를 소모하지 않게 하려는 것 — 시도 횟수는 <b>진짜 추측</b>만 센다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ConfirmIdentityVerificationRequest {

    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "인증번호는 6자리 숫자입니다.")
    private String otp;
}
