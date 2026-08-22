package com.diving.pungdong.certificate;

/** 검수 행의 상태. {@code PENDING} 만 큐에 뜬다. 나머지는 이력. */
public enum CertificateReviewStatus {
    PENDING, APPROVED, REJECTED
}
