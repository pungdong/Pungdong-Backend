package com.diving.pungdong.identityverification;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.identityverification.dto.IdentityVerificationRequest;
import com.diving.pungdong.identityverification.dto.IdentityVerificationResult;
import com.diving.pungdong.identityverification.dto.MyIdentityVerificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 본인확인(간편인증) 도메인 서비스. 계정 공유 자산 — 수강/강사 등 어느 플로우에서든 같은
 * 본인확인 레코드를 만들고 읽는다. 실제 검증은 {@link IdentityVerifier} 경계(stub/disabled)에 위임.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IdentityVerificationService {

    private final IdentityVerifier identityVerifier;
    private final IdentityVerificationJpaRepo identityVerificationRepo;
    private final AccountJpaRepo accountRepo;

    @Transactional
    public IdentityVerificationResult verify(Account account, IdentityVerificationRequest request) {
        Account managed = accountRepo.findById(account.getId())
                .orElseThrow(ResourceNotFoundException::new);
        IdentityVerification verification = identityVerifier.verify(managed, request);
        return IdentityVerificationResult.builder()
                .verificationId(verification.getId())
                .verified(true)
                .realName(verification.getRealName())
                .build();
    }

    /** 계정의 최신 본인확인 상태. 미인증이면 {verified:false} (404 아님). */
    public MyIdentityVerificationResponse getMyVerification(Account account) {
        return identityVerificationRepo.findTopByAccountIdOrderByIdDesc(account.getId())
                .map(v -> MyIdentityVerificationResponse.builder()
                        .verified(true)
                        .verificationId(v.getId())
                        .realName(v.getRealName())
                        .provider(v.getProvider())
                        .verifiedAt(v.getVerifiedAt())
                        .build())
                .orElseGet(MyIdentityVerificationResponse::notVerified);
    }
}
