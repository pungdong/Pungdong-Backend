package com.diving.pungdong.identityverification;

import com.diving.pungdong.account.Gender;

import java.time.LocalDateTime;

/**
 * 본인확인 외부 경계 — 구현 스왑({@code pungdong.identity-verification.mode})만으로
 * stub ↔ disabled ↔ 실 포트원/다날 연동을 바꾼다.
 *
 * <p>SMS 는 본질적으로 2단계라 경계도 <b>발송/확인</b>으로 나뉜다. 경계는 <b>외부 호출만</b>
 * 담당하고 영속화는 {@link IdentityVerificationService} 가 한다 (payment 의 {@code TossPaymentClient}
 * 와 동일 결 — 클라이언트는 record 를 반환, 서비스가 저장).
 *
 * <ul>
 *   <li>{@link #send} / {@link #resend} — OTP 문자 발송. 전송 실패(PG 장애)는 예외를 던진다.</li>
 *   <li>{@link #confirm} — OTP 확인. 성공=VERIFIED(+CI/DI), OTP 불일치=FAILED(errorCode). OTP
 *       불일치는 예외가 아니라 결과다(정상 분기). 만료·시도초과는 서비스가 선판정한다.</li>
 * </ul>
 *
 * <p>구현: {@link StubIdentityVerifier}(stub, 기본) / {@link DisabledIdentityVerifier}(disabled) /
 * {@link RealPortOneIdentityVerifier}(real).
 */
public interface IdentityVerifier {

    /** OTP 문자 발송. {@code portoneVerificationId} 기준. 전송 실패 시 예외. */
    SendResult send(SendCommand command);

    /** OTP 재발송. 전송 실패 시 예외. */
    SendResult resend(String portoneVerificationId);

    /** OTP 확인 — VERIFIED(customer 채움) 또는 FAILED(errorCode). 전송 실패만 예외. */
    ConfirmResult confirm(String portoneVerificationId, String otp);

    /** 발송 명령 — 서비스가 만든 portoneVerificationId + 본인확인 입력. */
    record SendCommand(
            String portoneVerificationId,
            String realName,
            String birth,
            Gender gender,
            String phoneNumber,
            Carrier carrier,
            IdentityVerificationMethod method) {}

    /** 발송 결과 — OTP 유효기한. */
    record SendResult(LocalDateTime otpExpiresAt) {}

    /**
     * 확인 결과. VERIFIED 면 {@code customer} 세팅·{@code errorCode} null,
     * FAILED 면 {@code customer} null·{@code errorCode} 세팅.
     */
    record ConfirmResult(
            IdentityVerificationStatus status,
            VerifiedCustomer customer,
            IdentityVerificationErrorCode errorCode) {

        public static ConfirmResult verified(VerifiedCustomer customer) {
            return new ConfirmResult(IdentityVerificationStatus.VERIFIED, customer, null);
        }

        public static ConfirmResult failed(IdentityVerificationErrorCode errorCode) {
            return new ConfirmResult(IdentityVerificationStatus.FAILED, null, errorCode);
        }
    }

    /**
     * 확인된 본인 정보 — 기관 반환값. {@code ci}/{@code di} 는 필수, 나머지는 non-null 이면
     * 서비스가 요청 입력 위에 덮어써 권위값으로 만든다(null 이면 요청값 유지).
     */
    record VerifiedCustomer(
            String ci,
            String di,
            String realName,
            String phoneNumber,
            Carrier carrier) {}
}
