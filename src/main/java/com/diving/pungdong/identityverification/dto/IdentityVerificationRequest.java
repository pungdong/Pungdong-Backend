package com.diving.pungdong.identityverification.dto;

import com.diving.pungdong.account.Gender;
import com.diving.pungdong.identityverification.Carrier;
import com.diving.pungdong.identityverification.IdentityProvider;
import com.diving.pungdong.identityverification.IdentityVerificationMethod;
import lombok.*;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 본인확인 생성(=SMS 발송) 요청. PII(실명·생년월일·휴대폰)를 담으므로 POST body 로만 받는다
 * (URL/쿼리에 PII 금지 — repo 규칙).
 *
 * <p>{@code method=SMS}(기본)면 다날 휴대폰 본인인증 — {@code carrier}(통신사) 필수. {@code provider}
 * 는 향후 간편인증(APP) 방식 전용이라 SMS 에선 선택(무시)이다.
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

    /** 통신사 — SMS 발송 대상. SMS 방식 필수. */
    @NotNull
    private Carrier carrier;

    /** 본인확인 방식 — null 이면 서비스가 SMS 로 간주. */
    private IdentityVerificationMethod method;

    /** 간편인증(APP) 공급자 — SMS 에선 선택(무시). */
    private IdentityProvider provider;

    /** 필수 약관 동의 (개인정보 수집·제3자(다날) 제공 등). 동의 없이는 본인확인 불가. */
    @AssertTrue(message = "필수 약관에 모두 동의해야 본인확인을 진행할 수 있습니다.")
    private boolean agreedRequiredTerms;
}
