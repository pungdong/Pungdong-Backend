package com.diving.pungdong.payment;

/**
 * 어떤 PG 로 결제하는가 — {@code pungdong.payment.mode} 로 <b>부팅 시 하나만</b> 활성화된다(런타임 주문별 라우팅 없음).
 *
 * <p>FE 는 {@code /payments/prepare} 응답의 이 값으로 <b>결제창 구동 방식을 분기</b>한다 — 같은 엔드포인트라도
 * 토스는 위젯(clientKey), KCP 는 표준결제창(거래등록 결과)으로 띄우는 방식이 다르기 때문.
 */
public enum PaymentProvider {

    /** 로컬/테스트 stub — 외부 PG 미호출. FE 는 결제창 없이 즉시 confirm 을 부를 수 있다. */
    STUB,

    /** 토스페이먼츠 결제위젯 v2. */
    TOSS,

    /** NHN KCP 표준결제(간편결제 포함). */
    KCP,

    /** KG이니시스 INIpay PRO 표준결제(카드+간편결제). */
    INICIS
}
