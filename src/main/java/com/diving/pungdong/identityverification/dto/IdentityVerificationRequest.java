package com.diving.pungdong.identityverification.dto;

import com.diving.pungdong.account.Gender;
import com.diving.pungdong.identityverification.Carrier;
import com.diving.pungdong.identityverification.IdentityProvider;
import com.diving.pungdong.identityverification.IdentityVerificationMethod;
import com.diving.pungdong.identityverification.KoreanMobileNumber;
import lombok.*;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 본인확인 생성(=SMS 발송) 요청. PII(실명·생년월일·휴대폰)를 담으므로 POST body 로만 받는다
 * (URL/쿼리에 PII 금지 — repo 규칙).
 *
 * <p>{@code method=SMS}(기본)면 다날 휴대폰 본인인증 — {@code carrier}(통신사) 필수. {@code provider}
 * 는 향후 간편인증(APP) 방식 전용이라 SMS 에선 선택(무시)이다.
 *
 * <p><b>형식 검증을 여기(DTO)에 두는 이유</b> — {@code @Valid} 는 컨트롤러 진입에서 돌아 서비스의
 * 발송 쿨다운 획득({@code acquireSendSlot})보다 <b>앞선다</b>. 그래서 오타 한 번이 사용자의 30초
 * 발송 쿨다운을 태우지 않고, 구조적으로 불가능한 값으로 <b>유료 외부 API(다날)를 호출하지도 않는다</b>.
 * 실존·해지·명의 일치 판정은 다날 몫 — 여기선 형식만 본다.
 *
 * <p>{@code phoneNumber}/{@code birth} 는 setter 에서 <b>구분자를 제거(정규화)한 뒤</b> 검증한다.
 * {@code "010-1234-5678"} → {@code "01012345678"}. 저장·다날 전송 모두 이 canonical 형태다.
 * (Jackson 이 유일한 생성 경로 — {@code @Builder} 는 setter 를 우회하니 테스트 전용으로만 쓸 것.)
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class IdentityVerificationRequest {

    @NotBlank
    @Size(max = 50)
    private String realName;

    /** yyyyMMdd (정규화 후). 달력 정합(2월 31일 등)까지는 안 보고 다날에 맡긴다. */
    @NotBlank
    @Pattern(regexp = "^(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$",
            message = "생년월일 형식이 올바르지 않습니다. (yyyyMMdd)")
    private String birth;

    @NotNull
    private Gender gender;

    /** 숫자만 (정규화 후). KR 전용 규칙 — {@link KoreanMobileNumber}. */
    @NotBlank
    @Pattern(regexp = KoreanMobileNumber.PATTERN, message = KoreanMobileNumber.MESSAGE)
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

    /** 하이픈·공백 등 구분자를 떼고 숫자만 남긴다. 정규화 결과가 빈 문자열이면 {@code @NotBlank} 가 잡는다. */
    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = digitsOnly(phoneNumber);
    }

    /** {@code "1998-09-14"} 같은 표기도 받아 {@code "19980914"} 로 통일한다. */
    public void setBirth(String birth) {
        this.birth = digitsOnly(birth);
    }

    private static String digitsOnly(String raw) {
        return raw == null ? null : raw.replaceAll("\\D", "");
    }
}
