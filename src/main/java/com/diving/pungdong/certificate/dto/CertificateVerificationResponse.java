package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.certificate.CertificateVerification;
import com.diving.pungdong.certificate.CertificateVerificationKind;
import com.diving.pungdong.certificate.CertificateVerificationStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 응답의 {@code verification} 블록 — 항상 존재(NONE 이면 나머지 null). */
@Getter
@AllArgsConstructor
public class CertificateVerificationResponse {
    private final CertificateVerificationStatus status;
    private final CertificateVerificationKind kind;
    private final String reason;
    private final OffsetDateTime requestedAt;
    private final OffsetDateTime reviewedAt;

    public static CertificateVerificationResponse of(CertificateVerification v) {
        return new CertificateVerificationResponse(v.getStatus(), v.getKind(), v.getReason(),
                v.getRequestedAt(), v.getReviewedAt());
    }
}
