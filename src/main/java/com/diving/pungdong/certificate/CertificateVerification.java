package com.diving.pungdong.certificate;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Lob;
import java.time.OffsetDateTime;

/**
 * 자격증에 붙는 검증 상태 묶음 — {@code StudentCertificate.verification}. 응답에도 같은 모양으로 나간다.
 *
 * <p>값 객체라 불변. 전이는 {@link StudentCertificate} 의 의도별 메서드({@code markPending} 등)가 새 인스턴스로
 * 갈아끼운다 — 필드를 하나씩 건드리면 "PENDING 인데 kind 가 null" 같은 반쪽 상태가 생긴다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CertificateVerification {

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private CertificateVerificationStatus status;

    /** 상태가 NONE 이면 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_kind", length = 20)
    private CertificateVerificationKind kind;

    /** REJECTED 일 때 반려 사유(신청 반려면 신청 사유 복사). */
    @Lob
    @Column(name = "verification_reason")
    private String reason;

    /** 마지막으로 PENDING 이 된 시각. */
    @Column(name = "verification_requested_at")
    private OffsetDateTime requestedAt;

    /** 마지막 승인/반려 시각. */
    @Column(name = "verification_reviewed_at")
    private OffsetDateTime reviewedAt;

    public static CertificateVerification none() {
        return new CertificateVerification(CertificateVerificationStatus.NONE, null, null, null, null);
    }

    public static CertificateVerification pending(CertificateVerificationKind kind, OffsetDateTime now) {
        return new CertificateVerification(CertificateVerificationStatus.PENDING, kind, null, now, null);
    }

    public CertificateVerification verified(OffsetDateTime now) {
        return new CertificateVerification(CertificateVerificationStatus.VERIFIED, kind, null, requestedAt, now);
    }

    public CertificateVerification rejected(String reason, OffsetDateTime now) {
        return new CertificateVerification(CertificateVerificationStatus.REJECTED, kind, reason, requestedAt, now);
    }

    public boolean is(CertificateVerificationStatus s) {
        return status == s;
    }

    /** 심사 중인 <b>신청</b>이 참조 중 — Rule C 의 "삭제·종목변경·하향 금지" 대상. */
    public boolean isUnderApplicationReview() {
        return status == CertificateVerificationStatus.PENDING && kind == CertificateVerificationKind.APPLICATION;
    }

    /** 인증마크·Rule C 의 "살아있는 검증" — VERIFIED 또는 PENDING. */
    public boolean countsAsVerifiedOrPending() {
        return status == CertificateVerificationStatus.VERIFIED || status == CertificateVerificationStatus.PENDING;
    }
}
