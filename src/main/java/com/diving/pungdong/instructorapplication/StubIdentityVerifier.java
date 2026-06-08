package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.instructorapplication.dto.IdentityVerificationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 🔒 deferred 본인확인 stub. 어떤 입력이든 즉시 VERIFIED 처리하고, CI/DI 는 입력에서 파생한
 * 결정적 mock 값으로 채운다 (실데이터 아님 — 실제 연동 전까지 자리표시자).
 *
 * <p>실제 본인확인기관 연동 시: 이 빈을 실 구현으로 교체하고, CI/DI 는 기관 응답값 + 암호화
 * 저장으로 전환한다. (memory: identity-verification-model)
 */
@Service
@ConditionalOnProperty(name = "pungdong.identity-verification.mode", havingValue = "stub", matchIfMissing = true)
@RequiredArgsConstructor
public class StubIdentityVerifier implements IdentityVerifier {

    private final IdentityVerificationJpaRepo identityVerificationRepo;

    @Override
    public IdentityVerification verify(Account account, IdentityVerificationRequest request) {
        String seed = request.getRealName() + "|" + request.getPhoneNumber() + "|" + request.getBirth();
        String fingerprint = Integer.toHexString(seed.hashCode());

        IdentityVerification verification = IdentityVerification.builder()
                .account(account)
                .realName(request.getRealName())
                .birth(request.getBirth())
                .gender(request.getGender())
                .phoneNumber(request.getPhoneNumber())
                .provider(request.getProvider())
                .ci("CI-STUB-" + fingerprint)
                .di("DI-STUB-" + fingerprint)
                .verifiedAt(LocalDateTime.now())
                .build();

        return identityVerificationRepo.save(verification);
    }
}
