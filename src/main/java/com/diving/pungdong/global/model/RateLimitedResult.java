package com.diving.pungdong.global.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 429 응답 body — 공통 실패 envelope({@code success/code/msg})에 <b>잔여 초</b> 하나만 더한다.
 *
 * <p>status 가 "거부" 를, body 가 "얼마나 기다려야 하는지" 를 알려준다. 절대시각이 아니라 초인 이유는
 * 기기 시계가 서버와 어긋나도 안 밀리게 하려는 것으로, {@code otpExpiresInSeconds}·
 * {@code paymentExpiresInSeconds}·{@code closesInSeconds} 와 같은 규약이다.
 */
@Getter
@Setter
public class RateLimitedResult extends CommonResult {

    private long retryAfterSeconds;
}
