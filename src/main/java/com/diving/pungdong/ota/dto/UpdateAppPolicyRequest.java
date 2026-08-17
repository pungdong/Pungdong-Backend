package com.diving.pungdong.ota.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * {@code PUT /admin/app/policy}(ADMIN) — <b>전체 치환</b>(어드민 폼이 전 필드를 다시 보낸다).
 *
 * <p><b>여기는 엄격하게 간다.</b> {@code POST /app/ota/devices} 의 {@code appVersion} 은 {@code "1.0"} 을
 * 허용하는데 여기는 3자리 고정인 것은 <b>의도된 비대칭</b>이다 — 저긴 관측(앱이 400 을 조용히 삼켜서
 * 기기가 사라진다)이고 여긴 제어(사람이 폼에 넣고 에러가 화면에 보이며, 애매한 값이 게이트를 잘못 여닫는다).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateAppPolicyRequest {

    @NotNull
    @Valid
    private PlatformPolicy ios;

    @NotNull
    @Valid
    private PlatformPolicy android;

    @Size(max = 500)
    private String message;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlatformPolicy {

        /** 형식이 깨진 채 저장되면 <b>앱의 비교가 조용히 틀린다</b>(전원 차단 또는 전원 통과). */
        @NotBlank(message = "최소 버전을 입력해주세요.")
        @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,5}$",
                message = "최소 버전은 1.0.0 형식으로 입력해주세요.")
        private String minVersion;

        @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,5}$",
                message = "최신 버전은 1.0.0 형식으로 입력해주세요.")
        private String latestVersion;

        @Pattern(regexp = "^https://.+", message = "스토어 주소는 https:// 로 시작해야 합니다.")
        @Size(max = 500, message = "스토어 주소가 너무 깁니다.")
        private String storeUrl;
    }
}
