package com.diving.pungdong.identityverification;

/**
 * 본인확인 레코드의 생명주기. SMS 방식은 발송 → 확인의 2단계라 생성 시점부터 상태를 가진다.
 *
 * <ul>
 *   <li>{@code READY}    — 레코드 생성 + OTP 문자 발송 완료. OTP 확인 대기.</li>
 *   <li>{@code VERIFIED} — OTP 확인 성공. CI/DI 적재됨. 신청/결제에서 재사용 가능.</li>
 *   <li>{@code FAILED}   — OTP 불일치/만료/시도초과. 재발송(resend) 또는 새 레코드로 재시도.</li>
 * </ul>
 *
 * <p>⚠️ "레코드 존재 = verified" 불변식이 이 상태 도입으로 깨진다 — 소비자(강사 신청 등)는
 * 반드시 {@code VERIFIED} 를 확인해야 한다. (READY/FAILED 레코드도 계정 소유로 존재)
 */
public enum IdentityVerificationStatus {
    READY, VERIFIED, FAILED
}
