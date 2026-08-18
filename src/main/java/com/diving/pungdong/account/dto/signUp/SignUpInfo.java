package com.diving.pungdong.account.dto.signUp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.diving.pungdong.global.validation.NickNamePolicy;
import com.diving.pungdong.global.validation.PasswordPolicy;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
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
    @NotEmpty(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    String email;

    @NotEmpty(message = "비밀번호를 입력해주세요.")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.MESSAGE)
    String password;

    /**
     * 공개 URL 식별자를 겸하므로 형식이 좁다 — {@link NickNamePolicy}. 예약어(브랜드·운영자 사칭·라우트
     * 충돌) 차단은 형식과 달리 어드민 예외가 있어 DTO 가 아니라 서비스 가드에서 본다.
     */
    @NotEmpty(message = "닉네임을 입력해주세요.")
    @Pattern(regexp = NickNamePolicy.PATTERN, message = NickNamePolicy.MESSAGE)
    String nickName;
}
