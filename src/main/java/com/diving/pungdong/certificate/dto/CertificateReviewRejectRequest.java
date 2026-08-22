package com.diving.pungdong.certificate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 반려 요청 — 사유 필수(신청자/보유자에게 그대로 노출). */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CertificateReviewRejectRequest {
    @NotBlank(message = "반려 사유를 입력해주세요.")
    @Size(max = 1000, message = "반려 사유는 1000자 이하로 입력해주세요.")
    private String reason;
}
