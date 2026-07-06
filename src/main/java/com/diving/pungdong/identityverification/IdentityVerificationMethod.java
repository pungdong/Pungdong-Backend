package com.diving.pungdong.identityverification;

/**
 * 본인확인 방식 판별자.
 *
 * <ul>
 *   <li>{@code SMS} — 휴대폰 문자(OTP) 본인인증. 다날 CPID 로 포트원 REST 를 호출하는 실 서비스 경로.
 *       CI/DI 를 안정적으로 반환한다.</li>
 *   <li>{@code APP} — 간편인증(카카오/네이버/토스 등). 향후 추가 대비로 예약된 값 — 현재 미사용.
 *       ({@link IdentityProvider} 어휘가 이 방식에 붙는다.)</li>
 * </ul>
 *
 * <p>실 서비스가 SMS 로 확정된 이유: 간편인증은 CI 미반환 케이스가 있어 CI/DI 확보가 불안정하다.
 * (memory: identity-verification-model)
 */
public enum IdentityVerificationMethod {
    SMS, APP
}
