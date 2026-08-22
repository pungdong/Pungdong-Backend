package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.certificate.StudentCertificate;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 공개 인증마크 1개 — 브랜딩·강의상세·프로필의 {@code CertBadge} 가 전부 이 값에서 매핑된다(형태는 v1 그대로).
 *
 * <p>{@code organizationOther} 는 {@code organizationCode == OTHER} 일 때 {@code organizationName}(자유입력 단체명
 * 스냅샷). 옛 {@code ApplicationCertificate.organizationOther} 의 자리를 새 필드 없이 메운다.
 */
@Getter
@AllArgsConstructor
public class VerifiedCertificateBadge {
    public static final String ORGANIZATION_OTHER = "OTHER";

    private final String disciplineCode;
    private final String organizationCode;
    private final String organizationOther;

    public static VerifiedCertificateBadge of(StudentCertificate c) {
        boolean other = ORGANIZATION_OTHER.equalsIgnoreCase(c.getOrganizationCode());
        return new VerifiedCertificateBadge(c.getDisciplineCode(), c.getOrganizationCode(),
                other ? c.getOrganizationName() : null);
    }
}
