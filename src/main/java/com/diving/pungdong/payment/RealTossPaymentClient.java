package com.diving.pungdong.payment;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 실 구현 — 토스페이먼츠 결제 승인 API 호출. 추가 의존성 없이 JDK {@link HttpClient} + Jackson
 * ({@link com.diving.pungdong.address.JusoAddressApiClient} 와 동일 형태).
 *
 * <p>핵심 주의:
 * <ul>
 *   <li><b>Basic 인증</b> — {@code Authorization: Basic base64(secretKey + ":")}. 비밀번호 없이 콜론만
 *       붙인 시크릿 키를 base64. 시크릿 키는 BE 밖으로 안 나간다.</li>
 *   <li><b>멱등</b> — {@code Idempotency-Key = orderId}. confirm 재시도(네트워크 타임아웃 등)에도 이중 승인 방지.</li>
 *   <li><b>금액</b> — 토스도 {@code amount} 로 승인 → 위젯 결제 금액과 다르면 토스가 거절(서버 권위 금액 강제).</li>
 * </ul>
 *
 * <p>{@code pungdong.payment.mode=toss} 일 때만 활성(staging/prod). 로컬 기본은 {@link StubTossPaymentClient}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.payment.mode", havingValue = "toss")
public class RealTossPaymentClient implements TossPaymentClient {

    private static final String CONFIRM_URL = "https://api.tosspayments.com/v1/payments/confirm";
    private static final String CANCEL_URL = "https://api.tosspayments.com/v1/payments/%s/cancel";

    private final String authHeader;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RealTossPaymentClient(
            @Value("${pungdong.payment.toss.secret-key:}") String secretKey,
            ObjectMapper objectMapper) {
        // Basic 인증: 시크릿 키 + ":" 를 base64 (비밀번호 없음).
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public TossConfirmResult confirm(String paymentKey, String orderId, int amount) {
        String body = "{\"paymentKey\":\"" + esc(paymentKey) + "\",\"orderId\":\"" + esc(orderId)
                + "\",\"amount\":" + amount + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(CONFIRM_URL))
                .timeout(Duration.ofSeconds(15)) // PG 망 왕복 — 넉넉히
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", orderId)
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
            return new TossConfirmResult(
                    json.path("status").asText(null),
                    json.path("method").asText(null),
                    parseTime(json.path("approvedAt").asText(null)),
                    json.path("receipt").path("url").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("toss confirm interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("toss confirm transport error", e);
        }
    }

    @Override
    public TossCancelResult cancel(String paymentKey, int cancelAmount, String reason) {
        String body = "{\"cancelReason\":\"" + esc(reason) + "\",\"cancelAmount\":" + cancelAmount + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(CANCEL_URL, paymentKey)))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", paymentKey + ":" + cancelAmount)
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
            return new TossCancelResult(json.path("status").asText(null), OffsetDateTime.now());
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
