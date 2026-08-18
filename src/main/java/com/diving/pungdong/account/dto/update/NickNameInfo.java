package com.diving.pungdong.account.dto.update;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.diving.pungdong.global.validation.NickNamePolicy;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

/**
 * {@code PATCH /account/nickName} 요청.
 *
 * <p>형식은 여기서({@link NickNamePolicy#PATTERN}), 예약어는 서비스 가드에서 본다 — 예약어는 어드민에게
 * 열어 줘야 해서 principal 을 아는 곳에서만 판정할 수 있다.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class NickNameInfo {
    @NotEmpty
    @Pattern(regexp = NickNamePolicy.PATTERN, message = NickNamePolicy.MESSAGE)
    private String nickName;
}
