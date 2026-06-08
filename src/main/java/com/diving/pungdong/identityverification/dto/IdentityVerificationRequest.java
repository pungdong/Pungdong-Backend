package com.diving.pungdong.identityverification.dto;

import com.diving.pungdong.account.Gender;
import com.diving.pungdong.identityverification.IdentityProvider;
import lombok.*;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 본인확인(간편인증) 요청. PII(실명·생년월일·휴대폰)를 담으므로 POST body 로만 받는다
 * (URL/쿼리에 PII 금지 — repo 규칙).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class IdentityVerificationRequest {

    @NotBlank
    private String realName;

    /** yyyyMMdd */
    @NotBlank
    private String birth;

    @NotNull
    private Gender gender;

    @NotBlank
    private String phoneNumber;

    @NotNull
    private IdentityProvider provider;

    /** 필수 약관 동의 (개인정보 수집·고유식별정보 처리 등). 동의 없이는 본인확인 불가. */
    @AssertTrue(message = "필수 약관에 모두 동의해야 본인확인을 진행할 수 있습니다.")
    private boolean agreedRequiredTerms;
}
