package com.diving.pungdong.certificate;

/**
 * 자격증 1건의 <b>검증 상태</b> — 공개 인증마크의 유일한 출처({@code VERIFIED} 만 마크).
 *
 * <pre>
 *   NONE ──(강사 신청에 첨부 / 승인 종목에서 강사레벨 등록·식별필드 수정)──▶ PENDING
 *   PENDING ──(어드민 승인)──▶ VERIFIED      PENDING ──(어드민 반려)──▶ REJECTED
 *   VERIFIED ──(식별필드 수정)──▶ PENDING(RE_VERIFY)
 *   REJECTED ──(수정)──▶ PENDING(ADDITIONAL)   *승인 종목일 때. 아니면 NONE
 * </pre>
 *
 * 전이는 전부 {@link CertificateVerificationService} 의 Rule A/B/C 에서만 일어난다.
 */
public enum CertificateVerificationStatus {
    NONE, PENDING, VERIFIED, REJECTED
}
