package com.diving.pungdong.account.dto.update;

import com.diving.pungdong.account.Gender;
import com.diving.pungdong.global.validation.BirthDate;
import com.diving.pungdong.global.validation.KoreanMobileNumber;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

/**
 * PUT /account 요청 — 프로필(생년월일·성별·휴대폰) 수정.
 *
 * <p>{@code birth}/{@code phoneNumber} 는 본인확인과 <b>동일한 형식·정규화</b>를 쓴다(공유 상수
 * {@link BirthDate}·{@link KoreanMobileNumber}). setter 에서 구분자를 떼고 canonical 형태로 저장하므로
 * {@code GET /account}(저장값 그대로 반환) 와 이 PUT 의 수용 형식이 항상 일치한다 — "GET 으로 받은
 * 값을 그대로 되돌려보내는" 프로필 수정 패턴에서 사용자가 건드리지 않은 필드로 400 이 나지 않는다.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AccountUpdateInfo {

    /** yyyyMMdd (정규화 후). 하이픈 표기(`1997-08-15`)도 받아 `19970815` 로 통일. */
    @NotBlank
    @Pattern(regexp = BirthDate.PATTERN, message = BirthDate.MESSAGE)
    private String birth;

    @NotNull
    private Gender gender;

    /** 숫자만 (정규화 후). 본인확인과 동일 규칙 — {@link KoreanMobileNumber}. */
    @NotBlank
    @Pattern(regexp = KoreanMobileNumber.PATTERN, message = KoreanMobileNumber.MESSAGE)
    private String phoneNumber;

    public void setBirth(String birth) {
        this.birth = digitsOnly(birth);
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = digitsOnly(phoneNumber);
    }

    private static String digitsOnly(String raw) {
        return raw == null ? null : raw.replaceAll("\\D", "");
    }
}
