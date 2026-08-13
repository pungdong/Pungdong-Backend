package com.diving.pungdong.payment;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.PaymentGatewayException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * KG이니시스 <b>INIpay PRO 표준결제</b> 어댑터 — 간편결제(카카오·네이버·토스·애플페이)가 카드 결제창 안에 노출되므로
 * {@code P_PAY_TYPE=CARD} 하나로 카드+간편결제를 모두 받는다. 가상계좌·계좌이체는 붙이지 않는다.
 *
 * <p><b>흐름</b> ("P_NEXT_URL = BE 콜백" 구조 — 앱 WebView 가 결제창의 form POST 본문을 못 읽어서):
 * <ol>
 *   <li>FE 가 {@code INIPayPro_v2.js} 를 로드하고 {@code INIPayPro.requestPayment(obj)} 로 결제창을 띄운다.
 *       {@code obj}(P_ 파라미터 + 서명 {@code P_CHKFAKE})는 {@link #initParams}가 만들어 내려준다 — 외부 호출 없음.</li>
 *   <li>인증이 끝나면 이니시스가 {@code P_NEXT_URL}(=BE {@code /payments/inicis/return})로 결과를 form POST 한다
 *       ({@code P_STATUS}, {@code P_AUTH_TID}, {@code P_IDCNAME}, ...). 세션이 없다(콜백엔 우리 JWT 가 없음).</li>
 *   <li>BE 가 서버간 승인({@code https://{P_IDCNAME}paypro.inicis.com/.../payAppl.ini})을 호출한다 —
 *       {@code P_AMT}/{@code P_MID}는 <b>주문 원본값</b>으로 보낸다({@link ConfirmCommand#amount()}).</li>
 * </ol>
 *
 * <p><b>보안</b>:
 * <ul>
 *   <li><b>서명</b> {@code P_CHKFAKE = Base64(SHA-512(P_AMT + P_OID + P_TIMESTAMP + hashKey))} — {@code hashKey}
 *       (HMAC 해시키)는 시크릿, env/SSM 으로만 주입. FE 엔 계산된 서명값만 내려간다.</li>
 *   <li><b>승인엔 서명이 없다</b> — 대신 (a) {@code P_AUTH_TID}는 우리 콜백에만 오고 (b) 승인 전문의 {@code P_AMT}를
 *       콜백값이 아니라 <b>주문 권위 금액</b>으로 보내 대조시킨다.</li>
 *   <li><b>SSRF</b> — 승인 호스트를 콜백값 {@code P_IDCNAME}으로 조립하므로({@code {idc}paypro.inicis.com}),
 *       {@link #idcHost}가 소문자 토큰만 허용해 호스트 주입을 막는다.</li>
 *   <li><b>환불</b>은 별도 {@code iniapi.inicis.com/v2/pg/refund}(전체)/{@code partialRefund}(부분), V2 JSON.
 *       {@code hashData = SHA-512hex(apiKey + mid + type + timestamp + dataJson)} — 해시에 쓴 {@code dataJson}과
 *       body 의 {@code data}가 <b>바이트 동일</b>해야 하므로 {@code data} 를 한 번만 직렬화해 양쪽에 쓴다.</li>
 * </ul>
 *
 * <p>테스트/운영은 <b>엔드포인트가 아니라 MID 로 갈린다</b>(테스트 {@code INIpayTest}) — 그래서 live
 * 플래그가 없다. 승인 호스트는 콜백({@code P_IDCNAME})이, 환불 호스트는 고정({@code iniapi})이다.
 *
 * <p>빈은 항상 등록되고 사용 여부는 {@link PaymentGatewayRegistry}가 정한다(신규 결제=전역 설정, 기존 주문 환불=
 * 주문에 박제된 provider). 자격증명이 비어 있으면 실제 호출 시점에 실패한다.
 */
@Slf4j
@Component
public class InicisPaymentGateway implements PaymentGateway {

    /** 승인 응답/환불 응답의 성공 코드. */
    private static final String OK = "00";
    /** 카드 결제창(간편결제 포함). 가상계좌/계좌이체는 안 받는다. */
    private static final String PAY_TYPE_CARD = "CARD";
    /** 승인(payAppl) 경로 — 호스트는 콜백 {@code P_IDCNAME}으로 조립한다. */
    private static final String PAYAPPL_PATH = "/payment/v1/rest/payAppl.ini";
    /** 환불(취소) — 테스트/운영 공통, MID 로 구분. */
    private static final String REFUND_URL = "https://iniapi.inicis.com/v2/pg/refund";
    private static final String PARTIAL_REFUND_URL = "https://iniapi.inicis.com/v2/pg/partialRefund";

    private static final DateTimeFormatter REFUND_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter APPL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter APPL_TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final ZoneId INICIS_ZONE = ZoneId.of("Asia/Seoul"); // 이니시스 시각/타임스탬프는 KST

    /** 승인 호스트 프리픽스로 허용하는 형태 — 소문자만(호스트 주입 방지). 예: fc, ks, stg. */
    private static final Pattern IDC_NAME = Pattern.compile("[a-z]{1,10}");
    /** JSON/전문을 깨는 문자 제거 — 상품명·취소사유(따옴표/역슬래시면 환불 hashData 바이트동일성이 깨진다). */
    private static final Pattern FORBIDDEN = Pattern.compile("[\"\\\\\\p{Cntrl}]");

    private final String mid;
    private final String hashKey;   // P_CHKFAKE 서명용(HMAC 해시키)
    private final String apiKey;    // 환불 hashData 용(INIAPI key)
    private final String clientIp;  // 환불 전문의 가맹점 서버 IP
    private final String retUrl;    // P_NEXT_URL — 인증결과 수신 BE 콜백
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public InicisPaymentGateway(
            @Value("${pungdong.payment.inicis.mid:}") String mid,
            @Value("${pungdong.payment.inicis.hash-key:}") String hashKey,
            @Value("${pungdong.payment.inicis.api-key:}") String apiKey,
            @Value("${pungdong.payment.inicis.client-ip:127.0.0.1}") String clientIp,
            @Value("${pungdong.payment.inicis.ret-url:}") String retUrl,
            ObjectMapper objectMapper) {
        this.mid = mid;
        this.hashKey = hashKey;
        this.apiKey = apiKey;
        this.clientIp = clientIp;
        this.retUrl = retUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        log.info("[payment-inicis] 초기화 mid={} hashKey={} apiKey={}",
                mid, hashKey == null || hashKey.isBlank() ? "없음" : "설정됨",
                apiKey == null || apiKey.isBlank() ? "없음" : "설정됨");
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.INICIS;
    }

    /**
     * 결제창 구동값(P_ 파라미터 + 서명). 외부 호출 없이 계산만 한다 — FE 가 {@code INIPayPro_v2.js}로 결제창을 띄운다.
     * ⚠️ 서명 {@code P_CHKFAKE}는 {@code P_AMT}·{@code P_OID}·{@code P_TIMESTAMP}만 덮으므로, 표시용 {@code P_GOODS}/
     * {@code P_UNAME}은 FE 가 바꿔도 금액/주문은 위조 불가.
     */
    @Override
    public Map<String, String> initParams(InitCommand command) {
        String amt = String.valueOf(command.amount());
        String oid = command.orderId();
        String timestamp = String.valueOf(System.currentTimeMillis());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("P_MID", mid);
        params.put("P_OID", oid);
        params.put("P_PAY_TYPE", PAY_TYPE_CARD);
        params.put("P_DEVICE_TYPE", command.mobile() ? "MOBILE" : "WEB");
        params.put("P_IDCCODE", "Y"); // IDC 센터 코드 사용(고정)
        params.put("P_AMT", amt);
        params.put("P_GOODS", sanitize(command.orderName()));
        params.put("P_UNAME", command.customerKey()); // 구매자 식별(내부 id, PII 아님). FE 가 표시명으로 덮을 수 있음
        params.put("P_NEXT_URL", retUrl);             // BE 콜백 — 클라이언트가 정하지 않는다(오픈 리다이렉트 방지)
        params.put("P_TIMESTAMP", timestamp);
        params.put("P_CHKFAKE", chkfake(amt, oid, timestamp, hashKey));
        params.put("P_CHARSET", "UTF-8");
        return params;
    }

    @Override
    public ConfirmResult confirm(ConfirmCommand command) {
        String idcName = command.require("P_IDCNAME");
        String url = "https://" + idcHost(idcName) + PAYAPPL_PATH;
        String raw = postForm(url, payApplBody(mid, command), "payAppl");
        return verifyApproval(parseKeyValue(raw), command);
    }

    /**
     * 승인 응답 검증 + 결과 조립 — 외부 호출 없는 순수 판단이라 테스트로 고정한다(전송은 {@link #confirm} 담당).
     * 승인엔 서명이 없어 <b>서버 권위 금액과의 대조가 유일한 금액 방어선</b>이므로, 불일치면 로그만 남기지 말고 거부한다.
     */
    static ConfirmResult verifyApproval(Map<String, String> res, ConfirmCommand command) {
        String status = res.getOrDefault("P_STATUS", "");
        if (!OK.equals(status)) {
            log.warn("[payment-inicis] 승인 거절 P_STATUS={} P_RMESG={}", status, res.get("P_RMESG"));
            throw new BadRequestException();
        }
        // 방어적 대조 — 승인액이 우리 권위 금액과 다르면 승인을 거부한다(로그만 남기고 통과 X).
        // 폴백을 command.amount() 로 두면 P_AMT 부재/파싱불가가 "일치"로 판정돼 대조가 무력화되므로 -1(불가값)로 둔다.
        int approved = parseInt(res.get("P_AMT"), -1);
        if (approved != command.amount()) {
            log.error("[payment-inicis] ⚠️ 승인액 불일치 — 승인 거부 tid={} 서버={} INICIS={}",
                    refundTid(res), command.amount(), approved);
            throw new BadRequestException();
        }
        String tid = refundTid(res);
        String method = methodLabel(res);
        // 감사로그(성공 경로도 반드시 남긴다) — tid 가 어느 필드(P_TID/P_APPL_TID)로 왔는지까지 드러낸다.
        log.info("[payment-inicis] 승인 완료 oid={} tid={} (P_TID={}, P_APPL_TID={}) method={} amount={}",
                command.orderId(), tid, res.get("P_TID"), res.get("P_APPL_TID"), method, approved);
        return new ConfirmResult(
                true,
                status,
                method,
                parseTime(res.get("P_APPL_DT"), res.get("P_APPL_TM")),
                null,   // 이니시스는 승인 응답으로 영수증 URL 을 주지 않는다
                tid);   // 환불에 쓰는 거래번호(P_TID 우선, 없으면 P_APPL_TID)
    }

    @Override
    public CancelResult cancel(String pgTransactionId, int cancelAmount, int remainingAmount, String reason) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("이니시스 환불 API 키가 설정되지 않았습니다(pungdong.payment.inicis.api-key)");
        }
        String type = refundType(cancelAmount, remainingAmount);
        String url = "partialRefund".equals(type) ? PARTIAL_REFUND_URL : REFUND_URL;
        String timestamp = LocalDateTime.now(INICIS_ZONE).format(REFUND_TIME);
        Map<String, Object> data = refundData(pgTransactionId, cancelAmount, remainingAmount, reason);

        JsonNode json = postJson(url, refundBody(apiKey, mid, clientIp, timestamp, type, data, objectMapper), "refund");
        String resultCode = json.path("resultCode").asText("");
        if (!OK.equals(resultCode)) {
            String resultMsg = json.path("resultMsg").asText("");
            log.warn("[payment-inicis] 환불 거절 resultCode={} resultMsg={}", resultCode, resultMsg);
            // 거절 사유를 예외에 실어 환불 이력(RefundOrder.failureCode/Message)에 박제 — 대사용.
            throw new PaymentGatewayException(resultCode, resultMsg);
        }
        log.info("[payment-inicis] 환불 완료 tid={} type={} 취소액={} resultCode={}",
                pgTransactionId, type, cancelAmount, resultCode);
        return new CancelResult(true, resultCode, refundTime(json));
    }

    /* ─── 전문(request body) 생성 — 네트워크 없이 검증 가능하도록 분리(package-private) ─── */

    /** {@code P_CHKFAKE = Base64(SHA-512(P_AMT + P_OID + P_TIMESTAMP + hashKey))}. */
    static String chkfake(String amt, String oid, String timestamp, String hashKey) {
        return Base64.getEncoder().encodeToString(sha512(amt + oid + timestamp + hashKey));
    }

    /**
     * 승인(payAppl) 전문 — form-urlencoded. ⚠️ {@code P_AMT}는 결제창이 준 값이 아니라 <b>주문 권위 금액</b>
     * ({@link ConfirmCommand#amount()})이다. {@code P_AUTH_TID}가 없으면 400.
     */
    static String payApplBody(String mid, ConfirmCommand command) {
        String authTid = command.require("P_AUTH_TID");
        return "P_MID=" + enc(mid)
                + "&P_AUTH_TID=" + enc(authTid)
                + "&P_AMT=" + command.amount()
                + "&P_CHARSET=UTF-8";
    }

    /** 취소 타입 — 잔액 일부면 부분취소(partialRefund), 잔액 전부(이상)면 전체취소(refund). */
    static String refundType(int cancelAmount, int remainingAmount) {
        return cancelAmount < remainingAmount ? "partialRefund" : "refund";
    }

    /**
     * 환불 {@code data} 객체. 부분취소면 {@code price}(취소액)+{@code confirmPrice}(<b>취소 후</b> 잔액)가 필수 —
     * 포트의 {@code remainingAmount} 는 취소 <b>직전</b> 잔액이므로 {@code remainingAmount - cancelAmount}로 변환한다.
     */
    static Map<String, Object> refundData(String tid, int cancelAmount, int remainingAmount, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tid", tid);
        data.put("msg", sanitize(reason));
        if (cancelAmount < remainingAmount) {
            data.put("price", String.valueOf(cancelAmount));
            data.put("confirmPrice", String.valueOf(remainingAmount - cancelAmount));
            data.put("currency", "WON");
            data.put("tax", "0");
            data.put("taxfree", "0");
        }
        return data;
    }

    /**
     * 환불(취소) 전문 — V2 JSON 문자열. ⚠️ {@code hashData}에 쓰는 {@code dataJson}과 body 의 {@code data}가
     * <b>바이트 동일</b>해야 이니시스 재계산과 맞는다 — 같은 {@code data} 맵을 한 번만 직렬화해 양쪽에 쓴다.
     * (샘플의 {@code replaceAll("\\","")} 재현 — 슬래시 이스케이프 대비, 우리 데이터엔 사실상 no-op.)
     */
    static String refundBody(String apiKey, String mid, String clientIp, String timestamp,
                             String type, Map<String, Object> data, ObjectMapper mapper) {
        try {
            String dataJson = mapper.writeValueAsString(data);
            String hashData = sha512hex((apiKey + mid + type + timestamp + dataJson).replaceAll("\\\\", ""));
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("mid", mid);
            outer.put("type", type);
            outer.put("timestamp", timestamp);
            outer.put("clientIp", clientIp);
            outer.put("data", data); // 같은 맵 → dataJson 과 바이트 동일하게 직렬화됨
            outer.put("hashData", hashData);
            return mapper.writeValueAsString(outer);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이니시스 환불 전문 직렬화 실패", e);
        }
    }

    /**
     * 승인 호스트 프리픽스 검증 후 조립 — {@code {idc}paypro.inicis.com}. 콜백값을 그대로 호스트에 붙이므로
     * 소문자 토큰만 허용해 {@code evil.com/} 같은 호스트 주입(SSRF)을 막는다.
     */
    static String idcHost(String idcName) {
        if (idcName == null || !IDC_NAME.matcher(idcName).matches()) {
            throw new BadRequestException();
        }
        return idcName + "paypro.inicis.com";
    }

    /**
     * 승인 응답의 결제수단을 표시용 한글 라벨로. 지금은 {@code P_TYPE}(CARD/BANK/VBANK) 기준 — 카드만 받으므로
     * 대개 "카드". 간편결제 세부 브랜드(카카오페이 등) 구분은 실 승인응답 필드 확인 후 확장(현재 미확정).
     */
    static String methodLabel(Map<String, String> res) {
        String type = res.getOrDefault("P_TYPE", "");
        switch (type) {
            case "CARD": return "카드";
            case "BANK": return "계좌이체";
            case "VBANK": return "가상계좌";
            default: return type.isBlank() ? null : type; // 미지 코드는 원문 유지(추적용)
        }
    }

    /** {@code &}-joined {@code key=value} 응답(payAppl)을 맵으로. 값은 ASCII(P_STATUS/P_TID/P_AMT 등)라 디코딩 불필요. */
    static Map<String, String> parseKeyValue(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) {
                continue;
            }
            map.put(pair.substring(0, i), pair.substring(i + 1));
        }
        return map;
    }

    /* ─── 내부 ─── */

    /** 환불 tid — 승인 응답의 거래번호. P_TID 우선, 없으면 P_APPL_TID 폴백. */
    private static String refundTid(Map<String, String> res) {
        String tid = res.get("P_TID");
        return tid == null || tid.isBlank() ? res.get("P_APPL_TID") : tid;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String postForm(String url, String body, String tag) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(req, tag).body();
    }

    private JsonNode postJson(String url, String body, String tag) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return objectMapper.readTree(send(req, tag).body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("inicis " + tag + " 응답 파싱 실패", e);
        }
    }

    private HttpResponse<String> send(HttpRequest req, String tag) {
        try {
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("[payment-inicis] {} HTTP {} body={}", tag, res.statusCode(), res.body());
                throw new BadRequestException();
            }
            return res;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("inicis " + tag + " interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("inicis " + tag + " transport error", e);
        }
    }

    /** 승인 응답 시각(P_APPL_DT=yyyyMMdd + P_APPL_TM=HHmmss, KST) → OffsetDateTime. 실패는 null(표시용). */
    private static OffsetDateTime parseTime(String date, String time) {
        if (date == null || date.isBlank() || time == null || time.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.of(
                            java.time.LocalDate.parse(date, APPL_DATE),
                            java.time.LocalTime.parse(time, APPL_TIME))
                    .atZone(INICIS_ZONE).toOffsetDateTime();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 환불 응답 시각 — 전체취소 cancelDate/cancelTime, 부분취소 prtcDate/prtcTime. 실패는 null. */
    private static OffsetDateTime refundTime(JsonNode json) {
        String date = json.path("cancelDate").asText(json.path("prtcDate").asText(""));
        String time = json.path("cancelTime").asText(json.path("prtcTime").asText(""));
        return parseTime(date, time);
    }

    private static byte[] sha512(String plain) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(plain.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 미지원", e); // JRE 표준 — 사실상 도달 불가
        }
    }

    private static String sha512hex(String plain) {
        return String.format("%0128x", new BigInteger(1, sha512(plain)));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** 전문/JSON 을 깨는 문자 제거 — 상품명/취소사유. Korean 등 일반 텍스트는 보존. */
    static String sanitize(String s) {
        return s == null ? "" : FORBIDDEN.matcher(s).replaceAll(" ").trim();
    }
}
