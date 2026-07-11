package com.diving.pungdong.account.dto.emailCheck;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;

/**
 * POST /sign/check/email 요청 — 이메일 중복 확인(PII 라 POST body).
 * 형식 오류는 400, 존재 여부는 200 {exists} — 유효한 이메일만 조회한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailInfo {
    @NotBlank
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;
}
