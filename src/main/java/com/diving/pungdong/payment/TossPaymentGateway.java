package com.diving.pungdong.payment;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 토스페이먼츠 결제위젯 v2 어댑터. 추가 의존성 없이 JDK {@link HttpClient} + Jackson
 * ({@link com.diving.pungdong.address.JusoAddressApiClient} 와 동일 형태).
 *
 * <p>핵심 주의:
 * <ul>
 *   <li><b>Basic 인증</b> — {@code Authorization: Basic base64(secretKey + ":")}. 비밀번호 없이 콜론만
 *       붙인 시크릿 키를 base64. 시크릿 키는 BE 밖으로 안 나간다(FE 엔 공개 clientKey 만).</li>
 *   <li><b>멱등</b> — {@code Idempotency-Key = orderId}. confirm 재시도(네트워크 타임아웃 등)에도 이중 승인 방지.</li>
 *   <li><b>금액</b> — 토스도 {@code amount} 로 승인 → 위젯 결제 금액과 다르면 토스가 거절(서버 권위 금액 강제).</li>
 * </ul>
 *
 * <p>빈은 항상 등록되고, 실제 사용 여부는 {@link PaymentGatewayRegistry} 가 정한다 — 신규 결제는
 * {@code pungdong.payment.mode}, 기존 주문의 환불은 <b>주문에 박제된 provider</b> 기준.
 */
@Slf4j
@Component
public class TossPaymentGateway implements PaymentGateway {

    private static final String CONFIRM_URL = "https://api.tosspayments.com/v1/payments/confirm";
    private static final String CANCEL_URL = "https://api.tosspayments.com/v1/payments/%s/cancel";

    private final String authHeader;
    private final String clientKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TossPaymentGateway(
            @Value("${pungdong.payment.toss.secret-key:}") String secretKey,
            @Value("${pungdong.payment.toss.client-key:}") String clientKey,
            ObjectMapper objectMapper) {
        // Basic 인증: 시크릿 키 + ":" 를 base64 (비밀번호 없음).
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.clientKey = clientKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.TOSS;
    }

    /** 위젯 구동값 — 외부 호출 없음. clientKey 는 공개값이라 FE 로 내려도 안전하다. */
    @Override
    public Map<String, String> initParams(InitCommand command) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clientKey", clientKey);
        params.put("customerKey", command.customerKey());
        return params;
    }

    @Override
    public ConfirmResult confirm(ConfirmCommand command) {
        String paymentKey = command.require("paymentKey");
        String body = "{\"paymentKey\":\"" + esc(paymentKey) + "\",\"orderId\":\"" + esc(command.orderId())
                + "\",\"amount\":" + command.amount() + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(CONFIRM_URL))
                .timeout(Duration.ofSeconds(15)) // PG 망 왕복 — 넉넉히
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", command.orderId())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(res.body());
            if (res.statusCode() / 100 != 2) {
                // 토스 에러(금액 불일치·이미 처리·잘못된 키 등) → 클라이언트 입력/상태 문제 = 400.
                log.warn("[payment-toss] confirm 거절 HTTP {} code={} msg={}",
                        res.statusCode(), json.path("code").asText(""), json.path("message").asText(""));
                throw new BadRequestException();
            }
            String status = json.path("status").asText(null);
            return new ConfirmResult(
                    "DONE".equals(status), // 토스 어휘 정규화 — 서비스는 approved 만 본다
                    status,
                    json.path("method").asText(null),
                    parseTime(json.path("approvedAt").asText(null)),
                    json.path("receipt").path("url").asText(null),
                    paymentKey); // 토스는 취소 식별자 = paymentKey (승인 전에 이미 알고 있음)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("toss confirm interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("toss confirm transport error", e);
        }
    }

    @Override
    public CancelResult cancel(String pgTransactionId, int cancelAmount, int remainingAmount, String reason) {
        // remainingAmount 는 토스에 불필요 — 토스는 cancelAmount 만으로 부분취소를 처리한다(이니시스는 confirmPrice 로 변환해 사용).
        String body = "{\"cancelReason\":\"" + esc(reason) + "\",\"cancelAmount\":" + cancelAmount + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(CANCEL_URL, pgTransactionId)))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", pgTransactionId + ":" + cancelAmount)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(res.body());
            if (res.statusCode() / 100 != 2) {
                log.warn("[payment-toss] cancel 거절 HTTP {} code={} msg={}",
                        res.statusCode(), json.path("code").asText(""), json.path("message").asText(""));
                throw new BadRequestException();
            }
            // 부분취소면 마지막 cancels[] 의 시각을 쓸 수 있으나, 표시엔 최상위 status + now 로 충분.
            String status = json.path("status").asText(null);
            return new CancelResult(
                    "CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status),
                    status,
                    OffsetDateTime.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("toss cancel interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("toss cancel transport error", e);
        }
    }

    private static OffsetDateTime parseTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso); // 토스 approvedAt 은 ISO-8601 offset (예: 2024-...+09:00)
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** JSON 문자열 값 escape — paymentKey/orderId 는 토스 규칙상 영숫자/-_ 라 실제론 안전하지만 방어적으로. */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
