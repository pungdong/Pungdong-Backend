package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.certificate.CertificateReviewKind;
import com.diving.pungdong.certificate.CertificateReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 검수 행 상세 — 종류에 따라 블록이 다르다:
 * NEW 는 {@code application}(본인확인 PII·보험) + {@code certificates}(첨부 전부),
 * ADDITIONAL 은 {@code certificates} 1장, RE_VERIFY 는 {@code certificates} 1장 + {@code previous}.
 */
@Getter
@Builder
@AllArgsConstructor
public class CertificateReviewDetail {
    private final Long reviewId;
    private final CertificateReviewKind kind;
    private final CertificateReviewStatus status;
    private final String disciplineCode;
    private final Long accountId;
    private final String nickName;
    private final String email;
    /** REJECTED 일 때 사유. */
    private final String reason;
    private final OffsetDateTime requestedAt;
    private final OffsetDateTime reviewedAt;
    private final String reviewerNickName;
    private final boolean verifiedCertificateMissing;
    /** NEW 만. */
    private final ApplicationReviewView application;
    private final List<AdminCertificateView> certificates;
    /** RE_VERIFY 만. */
    private final CertificateReviewPrevious previous;
}
