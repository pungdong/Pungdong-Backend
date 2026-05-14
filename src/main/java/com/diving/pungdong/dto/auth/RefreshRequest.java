package com.diving.pungdong.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotEmpty;

/**
 * Access token 갱신 요청 페이로드 — 클라이언트가 보유한 refresh token 을 본문으로 전달.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefreshRequest {
    @NotEmpty
    private String refreshToken;
}
