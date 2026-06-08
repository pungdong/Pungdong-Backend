package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

import javax.validation.constraints.NotBlank;

/** 어드민 반려 요청 — 사유 필수 (신청자에게 노출되어 재제출을 안내). */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RejectRequest {
    @NotBlank
    private String reason;
}
