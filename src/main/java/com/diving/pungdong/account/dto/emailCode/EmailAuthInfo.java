package com.diving.pungdong.account.dto.emailCode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * POST /email/code/verify 요청 — 이메일 인증코드 확인.
 * {@code code} 는 {@code EmailService} 가 생성하는 6자리 숫자 — 형식 위반은 400(발송된 코드와 대조 전).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailAuthInfo {
    @NotBlank
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "인증번호는 6자리 숫자입니다.")
    private String code;
}
