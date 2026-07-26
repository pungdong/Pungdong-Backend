package com.diving.pungdong.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KCP 표준결제 <b>인증결과 콜백</b> — 결제창이 Ret_URL 로 form POST 하는 {@code enc_data} 등을 BE 가 받아
 * <b>서버사이드 승인까지</b> 끝내고, 그 결과를 FE 로 <b>GET 리다이렉트</b>한다.
 *
 * <p><b>왜 BE 가 받나</b>(FE 핑퐁 #1~#3): 앱(WebView)은 결제창의 form POST 본문을 못 읽는다
 * ({@code onShouldStartLoadWithRequest} 는 GET 만 가로챔). 그래서 Ret_URL 을 FE 가 아니라 BE 로 두고, BE 가
 * 승인 후 GET(웹 URL / app 스킴 {@code plop://})으로 돌려주면 웹·앱 통일 + POST 문제 소거. TOSS/STUB 은 종전대로
 * FE 가 confirm 을 호출한다(위젯 성공은 GET 리다이렉트라 WebView 가 가로챔).
 *
 * <p><b>보안</b>: 이 엔드포인트는 {@code permitAll} — KCP POST 엔 우리 JWT 가 없다. 인증은 <b>KCP 암호데이터
 * (enc_data)</b>가 대신한다(위조 POST 는 KCP 승인 호출에서 거절). 소유권은 prepare 때 이미 주문에 묶였고, 콜백은
 * 그 주문의 상태확정만 한다.
 *
 * <p><b>오픈 리다이렉트 방지</b>: 리다이렉트 URL 은 클라이언트가 정하지 않는다 — 주문에 박제된 {@link PaymentClient}
 * (web/app)로 <b>BE 설정의 고정 allowlist</b>(success/fail × web/app 4개) 중 하나만 고른다.
 */
@Slf4j
@RestController
public class KcpReturnController {

    private final PaymentService paymentService;
    private final String webSuccess;
    private final String webFail;
    private final String appSuccess;
    private final String appFail;

    public KcpReturnController(
            PaymentService paymentService,
            @Value("${pungdong.payment.kcp.return-web-success:}") String webSuccess,
            @Value("${pungdong.payment.kcp.return-web-fail:}") String webFail,
            @Value("${pungdong.payment.kcp.return-app-success:plop://payment/success}") String appSuccess,
            @Value("${pungdong.payment.kcp.return-app-fail:plop://payment/fail}") String appFail) {
        this.paymentService = paymentService;
        this.webSuccess = webSuccess;
        this.webFail = webFail;
        this.appSuccess = appSuccess;
        this.appFail = appFail;
    }

    /**
     * KCP 결제창 → Ret_URL(=여기) 로 form POST. 필수: {@code ordr_idxx}(=우리 orderId), {@code enc_data},
     * {@code enc_info}, {@code tran_cd}. 주문 식별 → 승인 → 302 리다이렉트(성공/실패, web/app).
     *
     * <p>승인이 실패해도 KCP 에 에러를 던지지 않는다 — 사용자의 브라우저/WebView 는 어떻든 <b>실패 화면으로
     * 리다이렉트</b>되어야 한다. 그래서 승인 예외를 잡아 fail 리다이렉트로 매핑한다.
     */
    @PostMapping(value = "/payments/kcp/return", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> kcpReturn(@RequestParam Map<String, String> form) {
        String orderId = form.get("ordr_idxx");
        PaymentService.OrderRedirect redirect = orderId == null ? null : paymentService.callbackRedirect(orderId);
        if (redirect == null) {
            // 알 수 없는 주문(위조/오배송) — 어느 client 인지 모르니 web fail 로.
            log.warn("[payment-kcp] 콜백 — 알 수 없는 ordr_idxx={}", orderId);
            return found(target(PaymentClient.WEB, false, null, "unknown"));
        }
        try {
            paymentService.confirmByCallback(orderId, Map.of(
                    "enc_data", form.getOrDefault("enc_data", ""),
                    "enc_info", form.getOrDefault("enc_info", ""),
                    "tran_cd", form.getOrDefault("tran_cd", "")));
            return found(target(redirect.client(), true, orderId, redirect.orderNo()));
        } catch (RuntimeException e) {
            log.warn("[payment-kcp] 콜백 승인 실패 orderId={} : {}", orderId, e.toString());
            return found(target(redirect.client(), false, orderId, redirect.orderNo()));
        }
    }

    /** client(web/app) × 성공여부 → 고정 allowlist 베이스 + 쿼리(orderId·orderNo·status). */
    private String target(PaymentClient client, boolean success, String orderId, String orderNo) {
        String base = (client == PaymentClient.APP)
                ? (success ? appSuccess : appFail)
                : (success ? webSuccess : webFail);
        Map<String, String> query = new LinkedHashMap<>();
        if (orderId != null) {
            query.put("orderId", orderId);
        }
        if (success && orderNo != null) {
            query.put("orderNo", orderNo);
        }
        query.put("status", success ? "paid" : "failed");
        return appendQuery(base, query);
    }

    private static String appendQuery(String base, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(base);
        char sep = base.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> e : query.entrySet()) {
            sb.append(sep).append(e.getKey()).append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }

    /** 302 FOUND + Location — POST→GET 리다이렉트. WebView 가 이 GET(또는 plop:// 스킴)을 가로챈다. */
    private static ResponseEntity<Void> found(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
