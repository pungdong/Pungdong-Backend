package com.diving.pungdong.global.advice.exception;

/**
 * 요청이 너무 잦다 — HTTP 429.
 *
 * <p>기존 {@code BadRequestException}(400) 을 재사용하지 않은 이유: FE 가 "잠시 후 다시 시도" 를 검증 실패와
 * 구분해 보여줘야 하는데 400 으로 뭉뚱그리면 구분이 불가능하다. 반대로 본인확인 OTP 처럼
 * 200 + {@code retryAfterSeconds} 로 주지 않는 이유는, <b>요청이 처리되지 않았는데 200 이면 FE 가 성공으로
 * 오해</b>하기 때문이다(메시지 전송에서는 치명적).
 *
 * <p>비-400 도메인 코드는 이 레포에 이미 선례가 있다 — {@code CONCURRENT_MODIFICATION(-1021)},
 * {@code REFUND_BLOCKED(-1022)} 가 둘 다 HTTP 409 다. 관례 이탈이 아니다.
 *
 * <p>{@link #getRetryAfterSeconds()} 는 응답 body 에 실린다 — {@code closesInSeconds} 와 같은
 * "잔여 초" 규약(절대시각을 주면 기기 시계 오차만큼 어긋난다).
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
