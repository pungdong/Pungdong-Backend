package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.certificate.CertificateReview;
import com.diving.pungdong.course.CertLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** RE_VERIFY 대조용 — 최초 VERIFIED 시점의 식별값. 자격증 행은 이미 새 값이라 여기서만 볼 수 있다. */
@Getter
@AllArgsConstructor
public class CertificateReviewPrevious {
    private final String disciplineCode;
    private final String organizationCode;
    private final CertLevel level;
    private final String certificateNumber;

    public static CertificateReviewPrevious of(CertificateReview r) {
        return new CertificateReviewPrevious(r.getPreviousDisciplineCode(), r.getPreviousOrganizationCode(),
                r.getPreviousLevel(), r.getPreviousCertificateNumber());
    }
}
