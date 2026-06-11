package com.diving.pungdong.consent.dto;

import lombok.*;

import javax.validation.constraints.NotBlank;

/**
 * 동의한 약관 1건 참조 — {@code (key, version)}. 전문은 BE 가 Sanity 에서 직접 받아 박제하므로
 * FE 는 식별자만 보낸다(본문 위변조 방지).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AgreementRef {

    /** Sanity term 식별 키 (예: privacy_collect). */
    @NotBlank
    private String key;

    /** 동의한 약관 버전 (예: v1). FE 가 화면에서 본 그 버전. */
    @NotBlank
    private String version;
}
