package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.course.CertLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 어드민 검수 화면의 자격증 1장 — 풀 필드 + 사진 presigned + 검증 상태. 신청 상세·검수 큐 상세가 공유한다.
 * ADMIN 게이트 뒤에서만 나간다(사진은 PII).
 */
@Getter
@Builder
@AllArgsConstructor
public class AdminCertificateView {
    private final Long certificateId;
    private final String disciplineCode;
    private final String organizationCode;
    private final String organizationName;
    private final String organizationFullName;
    private final CertLevel level;
    private final String certificationDisplayName;
    /** 백필 행은 null. */
    private final String certificateNumber;
    /** 백필 행은 null. */
    private final LocalDate acquiredAt;
    private final String holderName;
    private final String photoViewUrl;
    private final CertificateVerificationResponse verification;

    public static AdminCertificateView of(StudentCertificate c, String holderName, String photoViewUrl) {
        return AdminCertificateView.builder()
                .certificateId(c.getId())
                .disciplineCode(c.getDisciplineCode())
                .organizationCode(c.getOrganizationCode())
                .organizationName(c.getOrganizationName())
                .organizationFullName(c.getOrganizationFullName())
                .level(c.getLevel())
                .certificationDisplayName(c.getCertificationDisplayName())
                .certificateNumber(c.getCertificateNumber())
                .acquiredAt(c.getAcquiredAt())
                .holderName(holderName)
                .photoViewUrl(photoViewUrl)
                .verification(CertificateVerificationResponse.of(c.getVerification()))
                .build();
    }
}
