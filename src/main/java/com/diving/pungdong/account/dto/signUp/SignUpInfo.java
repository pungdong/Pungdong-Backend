package com.diving.pungdong.account.dto.signUp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.diving.pungdong.global.validation.PasswordPolicy;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

/**
 * 일반(이메일+비밀번호) 회원가입 요청 페이로드.
 * <p>
 * 본인인증 / 이메일 검증 / 휴대폰 인증은 별도 흐름으로 분리됨 (예약 직전, 강사 등록 시 등).
 * 가입 단계에서 받는 필드는 의도적으로 최소화.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignUpInfo {
    @NotEmpty @Email
    String email;

    @NotEmpty
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.MESSAGE)
    String password;

    @NotEmpty
    String nickName;
}
