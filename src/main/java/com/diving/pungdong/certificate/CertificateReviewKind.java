package com.diving.pungdong.certificate;

/**
 * 어드민 검수 큐의 행 종류. {@link CertificateVerificationKind} 와 1:1 이지만 이름이 다르다 —
 * 큐에서는 "신청 1건(자격증 N장)" 이 {@code NEW} 한 행이고, 자격증 쪽에서는 그 N장이 각각 {@code APPLICATION} 이다.
 */
public enum CertificateReviewKind {
    /** 강사 신청 자체 — 행 = 신청(자격증 N장 + 본인확인 + 보험). approve 가 INSTRUCTOR 권한을 준다. */
    NEW,
    /** 승인 종목에 추가된 강사레벨 자격증 1장. approve 는 그 자격증만 VERIFIED. */
    ADDITIONAL,
    /** VERIFIED 자격증의 식별필드 수정 1장. previous 와 대조. */
    RE_VERIFY;

    public CertificateVerificationKind toVerificationKind() {
        switch (this) {
            case NEW: return CertificateVerificationKind.APPLICATION;
            case ADDITIONAL: return CertificateVerificationKind.ADDITIONAL;
            default: return CertificateVerificationKind.RE_VERIFY;
        }
    }
}
