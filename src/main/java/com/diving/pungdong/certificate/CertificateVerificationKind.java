package com.diving.pungdong.certificate;

/**
 * 지금 검증 상태가 <b>어느 경로</b>로 생겼나. 상태와 짝으로 읽는다(상태가 NONE 이면 null).
 *
 * <ul>
 *   <li>{@code APPLICATION} — 강사 신청에 첨부돼 신청과 함께 심사(Rule B). PENDING 이면 "심사 중인 신청이 참조 중".</li>
 *   <li>{@code ADDITIONAL} — 이미 승인된 종목에 <b>추가로</b> 올린 강사레벨 자격증(Rule A / 승인 sweep).</li>
 *   <li>{@code RE_VERIFY} — VERIFIED 자격증의 식별필드를 고쳐 <b>재검수</b>에 들어감(Rule A). 이전 값은 리뷰 행의 previous.</li>
 * </ul>
 */
public enum CertificateVerificationKind {
    APPLICATION, ADDITIONAL, RE_VERIFY
}
