package com.diving.pungdong.certificate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 탭 뱃지 — 모든 종류(NEW/ADDITIONAL/RE_VERIFY)를 합친 상태별 건수. */
@Getter
@Builder
@AllArgsConstructor
public class CertificateReviewCounts {
    private final long pending;
    private final long approved;
    private final long rejected;
    private final long total;
}
