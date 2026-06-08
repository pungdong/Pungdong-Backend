package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.instructorapplication.dto.IdentityVerificationRequest;

/**
 * 본인확인(간편인증) 경계. 구현 교체만으로 stub ↔ 실제 본인확인기관 연동을 바꾼다.
 *
 * <p>현재 유일 구현은 {@link StubIdentityVerifier}. 실제 KG이니시스/나이스 연동이 들어오면
 * 이 인터페이스의 새 구현(@Primary 또는 profile 분기)으로 교체하고, 신청 서비스/컨트롤러는
 * 손대지 않는다.
 */
public interface IdentityVerifier {

    /** 본인확인을 수행하고 VERIFIED 결과를 영속화해 반환한다. */
    IdentityVerification verify(Account account, IdentityVerificationRequest request);
}
