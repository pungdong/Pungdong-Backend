package com.diving.pungdong.identityverification;

import com.diving.pungdong.account.Gender;
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
 * <p>2026-08-12 다날 CPID 개통 후 라이브 검증 진행 중 — send 요청 형식은 실응답으로 확정:
 * SMS 방식은 {@code customer.identityNumber}(주민번호 앞 7자리) 필수, {@code birthDate}/{@code gender}
 * 는 보내지 않는다. <b>OTP 에러코드·confirm 응답 필드 경로는 아직 실응답 확정 전</b>(로그로 raw 를 남긴다).
 * {@code mode=real} + PORTONE_* env 일 때만 활성.
 *
 * <p>📋 <b>각 필드 형식의 권위 출처(우리 결정 vs 포트원/다날 요구)와 확정 체크리스트</b>는
 * {@code docs/architecture/identity-verification.md} 의 "외부 계약 — 포트원 v2 / 다날" 표.
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
                + "\"identityNumber\":\"" + esc(toIdentityNumber(c.birth(), c.gender())) + "\"},"
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

    /**
     * yyyyMMdd + Gender → 주민등록번호 앞 7자리(yyMMdd + 성별식별자). 포트원이 SMS 방식에서
     * {@code customer.identityNumber} 로 요구(2026-08-12 실응답 400 REQUIRED 로 확정).
     * 내국인 가정 — 외국인 식별자(5~8)는 foreignerType 실판별과 함께 후속(체크리스트 (f)).
     * {@code birth} 는 DTO {@code BirthDate} 검증을 통과한 8자리 숫자.
     */
    private static String toIdentityNumber(String birth, Gender gender) {
        int year = Integer.parseInt(birth.substring(0, 4));
        char genderDigit = year >= 2000
                ? (gender == Gender.MALE ? '3' : '4')
                : (gender == Gender.MALE ? '1' : '2');
        return birth.substring(2) + genderDigit;
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
