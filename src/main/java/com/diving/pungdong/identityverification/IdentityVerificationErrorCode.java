package com.diving.pungdong.identityverification;

/**
 * OTP 확인 실패 사유 — FE 가 문구를 매핑하기 위한 판별 코드.
 *
 * <p><b>HTTP 표현</b> (repo 규약: 정상 UI 상태는 200 + 결과 필드):
 * <ul>
 *   <li>{@code OTP_MISMATCH} / {@code OTP_EXPIRED} / {@code OTP_TOO_MANY_ATTEMPTS} —
 *       사용자가 OTP 를 다시 입력하면 되는 <b>정상 분기</b>. confirm 응답이
 *       {@code 200 {status:FAILED, errorCode}} 로 내려준다.</li>
 *   <li>{@code SMS_SEND_FAILED} — 발송/재발송 시 PG(포트원/다날) 전송 실패 = <b>인프라 장애</b>.
 *       200 이 아니라 {@code 400}(CommonResult 봉투 + 한국어 메시지)로 던진다.
 *       (토스 승인 실패가 BadRequestException 으로 처리되는 것과 동일 결.)</li>
 * </ul>
 */
public enum IdentityVerificationErrorCode {
    OTP_MISMATCH,
    OTP_EXPIRED,
    OTP_TOO_MANY_ATTEMPTS,
    SMS_SEND_FAILED
}
