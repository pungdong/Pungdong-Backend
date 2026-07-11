package com.diving.pungdong.account.dto.restore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * PATCH /account/deleted-state 요청 — 유예기간 내 탈퇴 계정 복구(이메일 인증코드로 본인확인).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountRestoreInfo {
    @NotBlank
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "인증번호는 6자리 숫자입니다.")
    private String emailAuthCode;
}
