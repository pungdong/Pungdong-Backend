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
 * 이니시스 INIpay PRO <b>인증결과 콜백</b> — 결제창이 {@code P_NEXT_URL}(=여기)로 form POST 하는 인증결과를 BE 가 받아
 * <b>서버사이드 승인까지</b> 끝내고, 그 결과를 FE 로 <b>GET 리다이렉트</b>한다. (앱 WebView 가 결제창의
 * form POST 본문을 못 읽어 P_NEXT_URL 을 BE 로 둔다.)
 *
 * <p><b>보안</b>: {@code permitAll} — 이니시스 POST 엔 우리 JWT 가 없다. 인증은 {@code P_AUTH_TID}(우리 콜백에만 옴)가
 * 대신하고, 승인 전문의 금액은 <b>주문 권위값</b>으로 보낸다. 리다이렉트 URL 은 클라이언트가 정하지 않고 주문에 박제된
 * {@link PaymentClient}(web/app)로 BE 설정의 고정 allowlist 중 하나만 고른다(오픈 리다이렉트 방지).
 */
@Slf4j
@RestController
public class InicisReturnController {

    private final PaymentService paymentService;
    private final String webSuccess;
    private final String webFail;
    private final String appSuccess;
    private final String appFail;

    public InicisReturnController(
            PaymentService paymentService,
            @Value("${pungdong.payment.inicis.return-web-success:}") String webSuccess,
            @Value("${pungdong.payment.inicis.return-web-fail:}") String webFail,
            @Value("${pungdong.payment.inicis.return-app-success:plop://payment/success}") String appSuccess,
            @Value("${pungdong.payment.inicis.return-app-fail:plop://payment/fail}") String appFail) {
        this.paymentService = paymentService;
        this.webSuccess = webSuccess;
        this.webFail = webFail;
        this.appSuccess = appSuccess;
        this.appFail = appFail;
    }

    /**
     * 이니시스 결제창 → P_NEXT_URL(=여기)로 form POST. 필수: {@code P_OID}(=우리 orderId), {@code P_STATUS}("00"=인증성공),
     * {@code P_AUTH_TID}, {@code P_IDCNAME}. 주문 식별 → (인증성공이면) 승인 → 302 리다이렉트(성공/실패, web/app).
     *
     * <p>인증 실패({@code P_STATUS != "00"})면 승인을 부르지 않고 바로 fail 리다이렉트. 승인이 실패해도 이니시스에
     * 에러를 던지지 않는다 — 사용자의 브라우저/WebView 는 어떻든 실패 화면으로 리다이렉트되어야 한다.
     */
    @PostMapping(value = "/payments/inicis/return", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> inicisReturn(@RequestParam Map<String, String> form) {
        String orderId = form.get("P_OID");
        log.info("[payment-inicis] 콜백 수신 P_OID={} P_STATUS={} P_IDCNAME={}",
                orderId, form.get("P_STATUS"), form.get("P_IDCNAME"));
        PaymentService.OrderRedirect redirect = orderId == null ? null : paymentService.callbackRedirect(orderId);
        if (redirect == null) {
            // 알 수 없는 주문(위조/오배송) — 어느 client 인지 모르니 web fail 로.
            log.warn("[payment-inicis] 콜백 — 알 수 없는 P_OID={}", orderId);
            return found(target(PaymentClient.WEB, false, null, "unknown"));
        }
        if (!"00".equals(form.get("P_STATUS"))) {
            // 인증 실패 — 승인 호출 없이 실패 리다이렉트.
            log.warn("[payment-inicis] 콜백 인증실패 orderId={} P_STATUS={} P_RMESG={}",
                    orderId, form.get("P_STATUS"), form.get("P_RMESG"));
            return found(target(redirect.client(), false, orderId, redirect.orderNo()));
        }
        try {
            paymentService.confirmByCallback(orderId, Map.of(
                    "P_AUTH_TID", form.getOrDefault("P_AUTH_TID", ""),
                    "P_IDCNAME", form.getOrDefault("P_IDCNAME", "")));
            log.info("[payment-inicis] 콜백 승인 성공 orderId={} client={} → 성공 리다이렉트", orderId, redirect.client());
            return found(target(redirect.client(), true, orderId, redirect.orderNo()));
        } catch (RuntimeException e) {
            log.warn("[payment-inicis] 콜백 승인 실패 orderId={} : {}", orderId, e.toString());
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
