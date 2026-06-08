package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.instructorapplication.dto.IdentityVerificationRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 운영 fail-closed — 실 본인확인기관 연동이 없는데 stub 도 끈 상태({@code
 * pungdong.identity-verification.mode=disabled})에서 활성. 호출 시 거부해 <b>가짜 본인확인이
 * 운영에서 통과되는 것</b>을 원천 차단한다.
 *
 * <p>의존성은 항상 충족(앱은 정상 기동)하되 본인확인 엔드포인트만 실패한다 — 컨텍스트 전체가
 * 죽지 않게.
 */
@Service
@ConditionalOnProperty(name = "pungdong.identity-verification.mode", havingValue = "disabled")
public class DisabledIdentityVerifier implements IdentityVerifier {

    @Override
    public IdentityVerification verify(Account account, IdentityVerificationRequest request) {
        throw new BadRequestException("본인확인 연동이 아직 활성화되지 않았습니다.");
    }
}
