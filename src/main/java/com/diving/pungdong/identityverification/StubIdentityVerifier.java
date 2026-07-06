package com.diving.pungdong.identityverification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 🔒 deferred 본인확인 stub — 실 다날/포트원 연동 없이 2단계 흐름을 흉내낸다.
 *
 * <ul>
 *   <li>{@link #send}/{@link #resend} — 문자 발송 없이 OTP 유효기한만 돌려준다(now + 3분).</li>
 *   <li>{@link #confirm} — <b>매직 OTP {@code "000000"}</b> 이면 VERIFIED + portoneId 파생 mock
 *       CI/DI. 그 외는 FAILED(OTP_MISMATCH). (만료·시도초과는 서비스가 선판정하므로 여기선
 *       불일치만 판단한다.)</li>
 * </ul>
 *
 * <p>실데이터 아님 — 실 연동은 {@link RealPortOneIdentityVerifier}({@code mode=real}). 이 stub 이
 * 테스트/로컬 기본 경로다.
 */
@Service
@ConditionalOnProperty(name = "pungdong.identity-verification.mode", havingValue = "stub", matchIfMissing = true)
public class StubIdentityVerifier implements IdentityVerifier {

    /** 테스트/로컬에서 성공 처리되는 고정 OTP. */
    public static final String MAGIC_OTP = "000000";
    private static final long OTP_TTL_SECONDS = 180;

    @Override
    public SendResult send(SendCommand command) {
        return new SendResult(LocalDateTime.now().plusSeconds(OTP_TTL_SECONDS));
    }

    @Override
    public SendResult resend(String portoneVerificationId) {
        return new SendResult(LocalDateTime.now().plusSeconds(OTP_TTL_SECONDS));
    }

    @Override
    public ConfirmResult confirm(String portoneVerificationId, String otp) {
        if (!MAGIC_OTP.equals(otp)) {
            return ConfirmResult.failed(IdentityVerificationErrorCode.OTP_MISMATCH);
        }
        // portoneId 파생 결정적 mock CI/DI (실데이터 아님). realName/phone/carrier 는 요청값 유지(null).
        String fingerprint = Integer.toHexString(portoneVerificationId.hashCode());
        return ConfirmResult.verified(new VerifiedCustomer(
                "CI-STUB-" + fingerprint,
                "DI-STUB-" + fingerprint,
                null, null, null));
    }
}
