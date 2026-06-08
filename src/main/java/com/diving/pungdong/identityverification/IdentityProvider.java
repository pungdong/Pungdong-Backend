package com.diving.pungdong.identityverification;

/**
 * 간편인증 공급자 (본인확인기관을 통해 본인확인을 수행하는 인증서 제공자).
 *
 * 디자인 핸드오프(features/instructor-apply)의 provider 타일과 1:1 대응.
 * 실제 본인확인기관(KG이니시스/나이스 등) 연동은 deferred 상태 —
 * 현재는 {@link StubIdentityVerifier} 가 어떤 provider 든 즉시 VERIFIED 로 처리한다.
 */
public enum IdentityProvider {
    KAKAO, NAVER, TOSS, PASS, KB, PAYCO
}
