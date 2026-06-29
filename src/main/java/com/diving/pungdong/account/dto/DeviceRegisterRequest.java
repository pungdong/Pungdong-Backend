package com.diving.pungdong.account.dto;

import com.diving.pungdong.account.DeviceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotEmpty;

/**
 * 디바이스 토큰 등록 요청 (POST /me/devices). platform 은 선택 — 보내면 저장(iOS/Android 구분,
 * 디버깅·통계용), 없으면 null. 정책/계약은 docs/features/push.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceRegisterRequest {
    @NotEmpty
    private String token;

    private DeviceType platform; // ANDROID | IOS (optional)
}
