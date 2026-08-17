package com.diving.pungdong.ota.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * {@code GET /app/policy}(비인증) · {@code PUT /admin/app/policy} 응답. TS 타입명은 {@code AppPolicy}.
 *
 * <p>semver 비교는 <b>앱이</b> 한다 — BE 는 문자열 저장 + 형식 검증만. 앱은 조회 실패(오프라인/타임아웃/5xx)를
 * <b>통과</b>로 처리한다(게이트가 사용자를 앱 밖에 가두면 안 된다).
 */
@Getter
@Builder
public class AppPolicyResponse {

    private final Platform ios;
    private final Platform android;

    /** 차단 화면 안내 문구(한국어). FE 가 그대로 렌더한다. */
    private final String message;

    @Getter
    @Builder
    public static class Platform {
        /** 정책 미설정이면 {@code "0.0.0"} = 전 버전 통과. */
        private final String minVersion;
        private final String latestVersion;
        private final String storeUrl;
    }
}
