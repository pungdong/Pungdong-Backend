package com.diving.pungdong.payment;

/**
 * 결제를 시작한 클라이언트 종류 — <b>이니시스 콜백 리다이렉트 타겟</b>을 고르는 데 쓴다.
 *
 * <p>이니시스 표준결제는 결제창이 인증결과를 <b>P_NEXT_URL 로 form POST</b> 하는데, 앱(WebView)은 그 POST 본문을 못 읽는다
 * ({@code onShouldStartLoadWithRequest} 는 GET 네비게이션만 가로챔). 그래서 P_NEXT_URL 을 BE 로 두고, BE 가
 * 승인까지 끝낸 뒤 <b>GET 리다이렉트</b>로 FE 에 돌려준다 — 이때 web URL 로 갈지 app 스킴({@code plop://})으로 갈지를
 * 이 값으로 정한다.
 *
 * <p><b>{@code mobile} 과 독립 축</b>이다: {@code mobile} 은 결제창 레이아웃(모바일/PC), {@code client} 는 리다이렉트
 * 타겟(web/app). 웹 모바일브라우저 = {@code mobile:true, client:WEB} 조합이 존재해 둘을 합칠 수 없다.
 *
 * <p>오픈 리다이렉트 방지: 리다이렉트 URL 은 클라이언트가 정하지 않고, 이 enum 이 <b>BE 설정의 고정 allowlist</b>
 * (web/app × success/fail 4개) 중 하나를 고르게 한다.
 */
public enum PaymentClient {

    WEB,
    APP;

    /** {@code "web"}/{@code "app"}(대소문자·공백 무관) → enum. 없거나 모르는 값이면 안전하게 {@link #WEB}. */
    public static PaymentClient from(String raw) {
        if (raw == null || raw.isBlank()) {
            return WEB;
        }
        try {
            return PaymentClient.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return WEB;
        }
    }
}
