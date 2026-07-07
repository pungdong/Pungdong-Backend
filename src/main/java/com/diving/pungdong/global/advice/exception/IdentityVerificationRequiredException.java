package com.diving.pungdong.global.advice.exception;

/**
 * 본인인증(휴대폰 SMS)이 <b>선행 조건</b>인 동작을 미인증 상태로 시도할 때. 정책상 수강생은 수강신청 전,
 * 강사는 강사 전환 전에 본인인증(최신 {@code IdentityVerification.status == VERIFIED})이 있어야 한다.
 *
 * <p>{@link PreLaunchException} 과 같은 "신청 게이트" 성격 — 요청 자체는 멀쩡하고 사용자가 선행 조건만
 * 안 채운 것이라 {@code 403} + <b>식별 가능한 코드(-1017)</b>로 내려, FE 가 다른 400(만석·잘못된 입력)과
 * 구분해 "본인인증 화면으로" 분기하게 한다.
 *
 * <p><b>주의</b>: 강사 신청의 "없는/남의 {@code verificationId}"(IDOR·잘못된 입력)는 이 예외가 아니라
 * {@code 400}(BadRequestException) 을 유지한다 — 그건 "본인인증하러 가라" 상태가 아니다. 오직
 * "본인인증 미완료(선행 필요)" 분기만 이 예외로 통일한다.
 */
public class IdentityVerificationRequiredException extends RuntimeException {
    public IdentityVerificationRequiredException() {
        super();
    }
}
