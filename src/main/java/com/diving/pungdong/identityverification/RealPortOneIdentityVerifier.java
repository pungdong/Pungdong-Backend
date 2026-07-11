package com.diving.pungdong.identityverification;

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
import java.time.ZoneOffset;

/**
 * 실 구현 — 포트원 REST v2 로 다날 휴대폰 본인인증(SMS) 호출. SDK 인증창 없이 서버가 REST 만으로
 * 진행한다(포트원 기술지원 회신). {@link com.diving.pungdong.payment.RealTossPaymentClient} 와 동일
 * 형태: 추가 의존성 없이 JDK {@link HttpClient} + Jackson.
 *
 * <p><b>인증</b> — {@code Authorization: PortOne {apiSecret}}. api-secret·store-id·channel-key 는
 * BE 전용 시크릿(밖으로 안 나감). 다날 CPID 는 포트원 채널(channel-key)에 매핑되어 있다.
 *
 * <p><b>흐름</b>:
 * <pre>
 *   POST /identity-verifications/{id}/send    {channelKey, customer, method:"SMS", operator}  → 문자 발송
 *   POST /identity-verifications/{id}/confirm {otp}                                            → VERIFIED + verifiedCustomer(ci/di)
 *   POST /identity-verifications/{id}/resend  {}                                               → 재발송
 * </pre>
 * {@code {id}} = 우리가 발급한 {@code portoneVerificationId}.
 *
 * <p>⚠️ <b>라이브 미검증</b>: 다날 CPID/통신사 심사(리드타임 최대 1주) 전에는 실호출을 검증할 수
 * 없다. 아래 요청/응답 매핑은 REST 명세 기반이며, <b>OTP 에러코드·응답 필드 경로는 개통 후 실응답으로
 * 보정</b>해야 한다(로그로 raw 응답을 남긴다). {@code mode=real} + PORTONE_* env 일 때만 활성.
 *
 * <p>📋 <b>각 필드 형식의 권위 출처(우리 결정 vs 포트원/다날 요구)와 개통 시 확정 체크리스트</b>는
 * {@code docs/architecture/identity-verification.md} 의 "외부 계약 — 포트원 v2 / 다날" 표.
 * 특히 {@code birthDate}(8자리 입력은 우리 편의, 포트원 전송은 {@code yyyy-MM-dd})·{@code phoneNumber}
 * (숫자만 = 추정) 는 개통 후 실응답으로 확정 대상.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.identity-verification.mode", havingValue = "real")
public class RealPortOneIdentityVerifier implements IdentityVerifier {

    private static final String BASE = "https://api.portone.io";
    private static final String SEND_URL = BASE + "/identity-verifications/%s/send";
    private static final String CONFIRM_URL = BASE + "/identity-verifications/%s/confirm";
    private static final String RESEND_URL = BASE + "/identity-verifications/%s/resend";

    private final String authHeader;
    private final String storeId;
    private final String channelKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RealPortOneIdentityVerifier(
            @Value("${pungdong.portone.api-secret:}") String apiSecret,
            @Value("${pungdong.portone.store-id:}") String storeId,
            @Value("${pungdong.portone.channel-key:}") String channelKey,
            ObjectMapper objectMapper) {
        this.authHeader = "PortOne " + apiSecret;
        this.storeId = storeId;
        this.channelKey = channelKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public SendResult send(SendCommand c) {
        String body = "{"
                + storeIdField()
                + "\"channelKey\":\"" + esc(channelKey) + "\","
                + "\"customer\":{"
                + "\"name\":\"" + esc(c.realName()) + "\","
                + "\"phoneNumber\":\"" + esc(c.phoneNumber()) + "\","
                + "\"birthDate\":\"" + esc(toIsoDate(c.birth())) + "\","
                + "\"gender\":\"" + esc(c.gender().name()) + "\"},"
                + "\"method\":\"" + esc(c.method().name()) + "\","
                + "\"operator\":\"" + esc(c.carrier().name()) + "\"}";
        postExpectOk(String.format(SEND_URL, c.portoneVerificationId()), body, "send");
        // 다날 SMS OTP 유효시간(관행상 3~5분). 실제 만료는 포트원/다날이 confirm 시 강제 — 여기선 표시값.
        return new SendResult(java.time.OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
    }

    @Override
    public SendResult resend(String portoneVerificationId) {
        postExpectOk(String.format(RESEND_URL, portoneVerificationId), "{" + trimTrailingComma(storeIdField()) + "}", "resend");
        return new SendResult(java.time.OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
    }

    @Override
    public ConfirmResult confirm(String portoneVerificationId, String otp) {
        String body = "{" + storeIdField() + "\"otp\":\"" + esc(otp) + "\"}";
        HttpResponse<String> res = post(String.format(CONFIRM_URL, portoneVerificationId), body, "confirm");
        JsonNode json = readJson(res.body());
        if (res.statusCode() / 100 != 2) {
            // OTP 불일치/만료/시도초과 — 포트원 에러. type/message 로 최대한 판별(개통 후 보정 대상).
            log.warn("[identity-portone] confirm 실패 HTTP {} type={} msg={}", res.statusCode(),
                    json.path("type").asText(""), json.path("message").asText(""));
            return ConfirmResult.failed(mapOtpError(json.path("type").asText("") + " " + json.path("message").asText("")));
        }
        JsonNode iv = json.has("identityVerification") ? json.path("identityVerification") : json;
        String status = iv.path("status").asText("");
        if (!"VERIFIED".equals(status)) {
            log.warn("[identity-portone] confirm 2xx 이나 status={} — 실패 처리", status);
            return ConfirmResult.failed(IdentityVerificationErrorCode.OTP_MISMATCH);
        }
        JsonNode vc = iv.path("verifiedCustomer");
        return ConfirmResult.verified(new VerifiedCustomer(
                nullIfBlank(vc.path("ci").asText("")),
                nullIfBlank(vc.path("di").asText("")),
                nullIfBlank(vc.path("name").asText("")),
                nullIfBlank(vc.path("phoneNumber").asText("")),
                parseCarrier(vc.path("operator").asText(""))));
    }

    /* ─── 내부 ─────────────────────────────────────────── */

    private HttpResponse<String> post(String url, String body, String op) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("portone " + op + " interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("portone " + op + " transport error", e);
        }
    }

    /** 발송/재발송 — 2xx 아니면 SMS_SEND_FAILED = 인프라 장애 → 400. */
    private void postExpectOk(String url, String body, String op) {
        HttpResponse<String> res = post(url, body, op);
        if (res.statusCode() / 100 != 2) {
            log.warn("[identity-portone] {} 실패 HTTP {} body={}", op, res.statusCode(), res.body());
            throw new BadRequestException("본인확인 문자 발송에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    private String storeIdField() {
        return (storeId == null || storeId.isBlank()) ? "" : "\"storeId\":\"" + esc(storeId) + "\",";
    }

    private static String trimTrailingComma(String s) {
        return s.endsWith(",") ? s.substring(0, s.length() - 1) : s;
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** yyyyMMdd → yyyy-MM-dd (포트원 birthDate 포맷). 이미 대시 있으면 그대로. */
    private static String toIsoDate(String birth) {
        if (birth != null && birth.length() == 8 && birth.chars().allMatch(Character::isDigit)) {
            return birth.substring(0, 4) + "-" + birth.substring(4, 6) + "-" + birth.substring(6, 8);
        }
        return birth;
    }

    private static IdentityVerificationErrorCode mapOtpError(String hint) {
        String h = hint.toUpperCase();
        if (h.contains("EXPIR")) {
            return IdentityVerificationErrorCode.OTP_EXPIRED;
        }
        if (h.contains("EXCEED") || h.contains("ATTEMPT") || h.contains("LIMIT")) {
            return IdentityVerificationErrorCode.OTP_TOO_MANY_ATTEMPTS;
        }
        return IdentityVerificationErrorCode.OTP_MISMATCH;
    }

    private static Carrier parseCarrier(String operator) {
        if (operator == null || operator.isBlank()) {
            return null;
        }
        try {
            return Carrier.valueOf(operator);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
