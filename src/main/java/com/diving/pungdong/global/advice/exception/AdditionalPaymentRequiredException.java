package com.diving.pungdong.global.advice.exception;

/**
 * 옮기려는 슬롯이 <b>지금보다 비싸서</b> 추가 결제 없이는 일정을 바꿀 수 없을 때.
 *
 * <p>{@link PreLaunchException}·{@link IdentityVerificationRequiredException} 과 같은 성격 — <b>요청 자체는
 * 멀쩡하고</b> 사용자가 선행 조건(차액 결제)만 안 채운 것이라, 일반 {@code -1011} 대신 <b>식별 가능한 코드
 * (-1018)</b> 로 내려 FE 가 "추가 결제하고 변경하기" 로 분기하게 한다.
 *
 * <p><b>왜 필요했나</b>: {@code POST /enrollments/rounds/{roundId}/reschedule} 은 실패 사유가 10가지인데
 * (확정된 회차·거절/취소된 회차·만석·슬롯 무효·예약가능시간 밖·장비 오류 …) 전부 {@code BadRequestException}
 * 무인자 → 같은 {@code -1011} + 같은 msg 였다. FE 는 문구 매칭 말고는 "금액이 올라서 거부" 를 가려낼 수단이
 * 없어, 사유와 무관하게 차액 결제 버튼을 띄우고 헛걸음(다시 400)을 감수하고 있었다.
 *
 * <p>HTTP 는 <b>400 유지</b>. {@code -1016}/{@code -1017} 은 권한성 게이트라 403 이지만, 이건 권한이 아니라
 * 금액 조건이고 FE 가 이미 400 경로에서 처리 중이라 상태코드를 흔들 이유가 없다.
 *
 * <p>다음 행동은 차액 견적 → 결제: {@code POST /payments/prepare} 에 {@code target*} 4필드를 실어 보내면
 * 서버가 차액을 계산해 결제창을 연다.
 */
public class AdditionalPaymentRequiredException extends RuntimeException {
    public AdditionalPaymentRequiredException() {
        super();
    }
}
