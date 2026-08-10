package com.diving.pungdong.global.advice.exception;

/**
 * PG 가 <b>취소(환불) 요청을 거절</b>했을 때 어댑터가 던진다. PG 가 준 진단 정보({@code code}/{@code detail})를
 * 실어 나른다 — 환불 시도 이력({@code RefundOrder.failureCode/failureMessage})에 박제해 <b>대사(reconciliation)</b> 에 쓴다.
 *
 * <p><b>왜 {@link BadRequestException} 이 아닌가</b>: {@code BadRequestException} 은 메시지가 있으면
 * {@code ExceptionAdvice} 가 그대로 응답 {@code msg} 로 내보낸다. PG 내부 코드("resultCode=xx")를 강사 화면에
 * 노출할 이유가 없으므로 별도 타입으로 분리하고, 응답은 일반 400 문구로 고정한다(진단은 DB·로그에만).
 *
 * <p>전송 실패(타임아웃·파싱 오류)는 이 예외가 아니라 {@code IllegalStateException} 이다 — "PG 가 거절함"과
 * "PG 에 못 물어봄"은 대사에서 다르게 취급해야 한다(후자는 <b>취소가 실제로 됐는지 모름</b>).
 */
public class PaymentGatewayException extends RuntimeException {

    private final String code;
    private final String detail;

    public PaymentGatewayException(String code, String detail) {
        super("PG 취소 거절 code=" + code + " detail=" + detail);
        this.code = code;
        this.detail = detail;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }
}
