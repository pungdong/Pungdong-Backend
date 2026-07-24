package com.diving.pungdong.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;

/** 결제 준비 요청 — 수락된(PAYMENT_PENDING) 수강신청에 대해 주문 생성. */
@Getter @Setter
@NoArgsConstructor
public class PaymentPrepareRequest {

    @NotNull
    private Long enrollmentId;

    /**
     * 모바일 환경 여부 — KCP 표준결제가 <b>모바일(거래등록 후 PayUrl 이동)과 PC(JS SDK 직접 호출)</b>로 흐름이
     * 갈리기 때문에 필요하다. 토스 위젯은 무관(무시). 미전달 시 PC 로 본다.
     *
     * <p>보안값이 아니라 흐름 선택값이라 클라이언트가 보내도 안전하다 — 리턴 URL 처럼 위조 시 위험한 값은
     * 받지 않고 BE 설정으로 고정한다.
     */
    private boolean mobile;
}
