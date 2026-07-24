package com.diving.pungdong.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

import java.util.Map;

/**
 * 결제 승인 요청 — 결제창이 FE 로 돌려준 PG 고유 인증값을 그대로 전달한다. 서버는 {@code amount} 를 신뢰하지 않고
 * 주문의 권위 금액과 대조한다(불일치 시 거절).
 *
 * <p>{@code pgPayload} 에 담을 키는 PG 마다 다르다:
 * <ul>
 *   <li>{@code TOSS} — {@code paymentKey} (위젯 성공 리다이렉트의 값)</li>
 *   <li>{@code KCP} — {@code enc_data}, {@code enc_info}, {@code tran_cd} (결제창이 Ret_URL 로 POST 한 값)</li>
 *   <li>{@code STUB} — 비어도 됨</li>
 * </ul>
 *
 * <p>PG 마다 키가 달라 여기서 형식 검증을 걸 수 없다 — <b>필수값 검증은 각 게이트웨이 어댑터</b>가
 * {@code ConfirmCommand.require(key)} 로 수행하고, 없으면 400 이다.
 */
@Getter @Setter
@NoArgsConstructor
public class PaymentConfirmRequest {

    @NotBlank
    private String orderId;

    @NotNull
    @Positive
    private Integer amount;

    /** PG 고유 인증값(위 표). null/빈 값 허용 — 필요한 키가 없으면 어댑터가 400 을 던진다. */
    private Map<String, String> pgPayload;
}
