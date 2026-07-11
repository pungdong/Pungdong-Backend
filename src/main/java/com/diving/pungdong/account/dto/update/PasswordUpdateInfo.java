package com.diving.pungdong.account.dto.update;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.diving.pungdong.global.validation.PasswordPolicy;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * PATCH /account/password 요청 — 로그인 상태에서 비밀번호 변경.
 * ({@code newPassword} 최소 길이(@Size)는 FE 규칙 확인 후 — 입력검증 부류 2.)
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PasswordUpdateInfo {
    @NotBlank(message = "현재 비밀번호를 입력해주세요.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.MESSAGE)
    private String newPassword;
}
