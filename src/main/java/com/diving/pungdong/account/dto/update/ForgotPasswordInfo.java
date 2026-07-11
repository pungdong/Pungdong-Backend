package com.diving.pungdong.account.dto.update;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * PUT /account/forgot-password 요청 — 이메일 인증코드로 비밀번호 재설정.
 * ({@code newPassword} 최소 길이(@Size)는 FE 규칙 확인 후 — 입력검증 부류 2.)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForgotPasswordInfo {
    @NotBlank
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    private String newPassword;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "인증번호는 6자리 숫자입니다.")
    private String authCode;
}
