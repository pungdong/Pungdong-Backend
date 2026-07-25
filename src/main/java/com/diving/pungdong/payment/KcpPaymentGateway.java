package com.diving.pungdong.payment;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NHN KCP <b>표준결제</b> 어댑터 — 간편결제(카카오페이·네이버페이·토스페이·페이코·삼성/애플페이)가 표준 결제창에
 * 노출되므로 LITE PAY 가 아닌 표준결제를 쓴다.
 *
 * <p><b>흐름</b> (PC/모바일이 갈린다 — {@link InitCommand#mobile()}):
 * <ol>
 *   <li><b>모바일</b> — BE 가 거래등록({@code /trade/register.do}) → {@code approvalKey}·{@code PayUrl}·{@code traceNo}
 *       를 FE 로. FE 가 그 PayUrl 로 form POST 해 결제창 진입.</li>
 *   <li><b>PC</b> — 거래등록 <b>없음</b>. FE 가 {@code kcp_spay_hub.js} 의 {@code KCP_Pay_Execute_Web()} 로 바로 호출.</li>
 *   <li>결제창이 {@code Ret_URL} 로 {@code enc_data}·{@code enc_info}·{@code tran_cd}·{@code pay_type} 을 돌려줌
 *       → FE 가 {@code /payments/confirm} 의 {@code pgPayload} 로 전달.</li>
 *   <li>BE 가 승인({@code /gw/enc/v1/payment}) — {@code ordr_mony}/{@code ordr_no} 는 <b>가맹점 DB 원본값</b>으로
 *       보내 KCP 가 위변조를 검증한다(문서 명시 요구사항).</li>
 * </ol>
 *
 * <p><b>인증 수단이 두 가지</b>다:
 * <ul>
 *   <li>승인 — {@code kcp_cert_info}(서비스 인증서 PEM 직렬화).</li>
 *   <li>취소 — 위에 더해 {@code kcp_sign_data} = {@code site_cd^tno^mod_type} 을 <b>SHA256withRSA 서명</b>한 값.
 *       즉 <b>개인키</b>가 필요하다. 인증서/개인키 모두 시크릿이라 env/SSM 으로만 주입한다.</li>
 * </ul>
 *
 * <p>빈은 항상 등록되고, 실제 사용 여부는 {@link PaymentGatewayRegistry} 가 정한다 — 신규 결제는
 * {@code pungdong.payment.mode}, 기존 주문의 환불은 <b>주문에 박제된 provider</b> 기준.
 * (설정이 kcp 가 아니어도 빈은 뜨지만, 자격증명이 비어 있으면 실제 호출 시점에 실패한다.)
 */
@Slf4j
@Component
public class KcpPaymentGateway implements PaymentGateway {

    /* 테스트/운영 엔드포인트 — live 플래그로 <b>세트 단위</b> 전환(부분 혼용 사고 방지). */
    private static final String TEST_REGISTER = "https://testsmpay.kcp.co.kr/trade/register.do";
    private static final String TEST_APPROVAL = "https://stg-spl.kcp.co.kr/gw/enc/v1/payment";
    private static final String TEST_CANCEL = "https://stg-spl.kcp.co.kr/gw/mod/v1/cancel";
    private static final String LIVE_REGISTER = "https://smpay.kcp.co.kr/trade/register.do";
    private static final String LIVE_APPROVAL = "https://spl.kcp.co.kr/gw/enc/v1/payment";
    private static final String LIVE_CANCEL = "https://spl.kcp.co.kr/gw/mod/v1/cancel";

    private static final String OK = "0000";
    /** 결제수단 코드 — 카드(간편결제 포함). 계좌이체 PABK / 휴대폰 PAMC. */
    private static final String DEFAULT_PAY_TYPE = "PACA";
    private static final DateTimeFormatter KCP_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId KCP_ZONE = ZoneId.of("Asia/Seoul"); // KCP 응답 시각은 KST

    /** KCP 가 금지하는 특수문자 — 상품명 등에 섞이면 거래등록이 거절된다. */
    private static final String FORBIDDEN = "[,&;\\n\\r\\\\|'\"<>]";

    private final String siteCd;
    private final String certInfo;
    private final String retUrl;
    private final PrivateKey privateKey; // 취소 서명용. 미설정이면 null → cancel 시 명시적 실패.
    private final String registerUrl;
    private final String approvalUrl;
    private final String cancelUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // 생성자가 둘(운영/테스트)이라 Spring 이 쓸 것을 명시 — 없으면 no-arg 를 찾다 실패한다.
    @Autowired
    public KcpPaymentGateway(
            @Value("${pungdong.payment.kcp.site-cd:}") String siteCd,
            @Value("${pungdong.payment.kcp.cert-info:}") String certInfo,
            @Value("${pungdong.payment.kcp.private-key:}") String privateKeyPem,
            @Value("${pungdong.payment.kcp.private-key-password:}") String privateKeyPassword,
            @Value("${pungdong.payment.kcp.ret-url:}") String retUrl,
            @Value("${pungdong.payment.kcp.live:false}") boolean live,
            ObjectMapper objectMapper) {
        // 엔드포인트는 live 로 <b>세트 단위</b> 결정 — 운영 경로에서 개별 URL 을 주입할 방법을 두지 않는다
        // (테스트 등록 + 운영 승인 같은 혼용 사고 방지).
        this(siteCd, certInfo, privateKeyPem, privateKeyPassword, retUrl,
                live ? LIVE_REGISTER : TEST_REGISTER,
                live ? LIVE_APPROVAL : TEST_APPROVAL,
                live ? LIVE_CANCEL : TEST_CANCEL,
                objectMapper);
        log.info("[payment-kcp] 초기화 siteCd={} live={} 개인키={}", siteCd, live, privateKey != null ? "설정됨" : "없음");
    }

    /** 테스트 전용 — 로컬 스텁 서버로 엔드포인트를 돌려 HTTP 왕복/전문/서명을 검증하기 위한 생성자. */
    KcpPaymentGateway(String siteCd, String certInfo, String privateKeyPem, String privateKeyPassword,
                      String retUrl, String registerUrl, String approvalUrl, String cancelUrl,
                      ObjectMapper objectMapper) {
        this.siteCd = siteCd;
        this.certInfo = certInfo;
        this.retUrl = retUrl;
        this.privateKey = parsePrivateKey(privateKeyPem, privateKeyPassword);
        this.registerUrl = registerUrl;
        this.approvalUrl = approvalUrl;
        this.cancelUrl = cancelUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.KCP;
    }

    /**
     * 결제창 구동값. 모바일이면 여기서 <b>거래등록을 실제로 호출</b>하고, PC 면 외부 호출 없이 사이트코드만 내려준다.
     */
    @Override
    public Map<String, String> initParams(InitCommand command) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("siteCd", siteCd);
        params.put("payMethod", "CARD"); // 카드 결제창(간편결제 포함). FE 가 *_direct 로 단독호출 가능.
        params.put("retUrl", retUrl);    // BE 고정값 — 클라이언트가 정하지 않는다(오픈 리다이렉트 방지)
        params.put("customerKey", command.customerKey()); // KCP 결제창의 shop_user_id (계정 식별, PII 아님)
        if (!command.mobile()) {
            return params; // PC 는 거래등록 없이 JS SDK 로 바로 결제창 호출
        }

        JsonNode json = post(registerUrl, registerBody(siteCd, retUrl, command), "register");
        String code = json.path("Code").asText("");
        if (!OK.equals(code)) {
            log.warn("[payment-kcp] 거래등록 거절 Code={} Message={}", code, json.path("Message").asText(""));
            throw new BadRequestException();
        }
        params.put("approvalKey", json.path("approvalKey").asText(null));
        params.put("payUrl", json.path("PayUrl").asText(null));
        params.put("traceNo", json.path("traceNo").asText(null));
        return params;
    }

    @Override
    public ConfirmResult confirm(ConfirmCommand command) {
        JsonNode json = post(approvalUrl, confirmBody(siteCd, certInfo, command), "confirm");
        String resCd = json.path("res_cd").asText("");
        if (!OK.equals(resCd)) {
            log.warn("[payment-kcp] 승인 거절 res_cd={} res_msg={}", resCd, json.path("res_msg").asText(""));
            throw new BadRequestException();
        }
        // 방어적 대조 — KCP 는 ordr_mony 로 이미 검증하지만, 승인액이 우리 권위 금액과 다르면 즉시 드러나야 한다.
        int approved = json.path("amount").asInt(command.amount());
        if (approved != command.amount()) {
            log.error("[payment-kcp] ⚠️ 승인액 불일치 tno={} 서버={} KCP={}",
                    json.path("tno").asText(""), command.amount(), approved);
        }
        return new ConfirmResult(
                true,
                resCd,
                methodLabel(json), // PACA+card_other_pay_type / 머니(PAKM…) / 포인트(PANP) 3갈래를 표시용으로 정규화
                parseTime(json.path("app_time").asText(null)),
                null, // KCP 는 영수증 URL 을 승인 응답으로 주지 않는다
                json.path("tno").asText(null)); // 취소에 쓰는 KCP 거래 고유번호
    }

    @Override
    public CancelResult cancel(String pgTransactionId, int cancelAmount, int remainingAmount, String reason) {
        if (privateKey == null) {
            throw new IllegalStateException("KCP 취소 서명용 개인키가 설정되지 않았습니다(pungdong.payment.kcp.private-key)");
        }
        String modType = modType(cancelAmount, remainingAmount);
        String signData = sign(siteCd + "^" + pgTransactionId + "^" + modType);
        Map<String, Object> body = cancelBody(siteCd, certInfo, pgTransactionId, signData,
                cancelAmount, remainingAmount, reason);

        JsonNode json = post(cancelUrl, body, "cancel");
        String resCd = json.path("res_cd").asText("");
        if (!OK.equals(resCd)) {
            log.warn("[payment-kcp] 취소 거절 res_cd={} res_msg={}", resCd, json.path("res_msg").asText(""));
            throw new BadRequestException();
        }
        // 머니/포인트 결제 취소면 현금영수증 취소대상 금액이 따라온다. 발급/취소를 KCP 가 대행하는지
        // 가맹점이 직접 관리하는지에 따라 후속 처리가 갈려서, 우선 감사 로그로 남긴다(운영 확인 필요).
        int cashReceipt = json.path("app_cash_receipt_mny").asInt(0);
        if (cashReceipt > 0) {
            log.warn("[payment-kcp] 현금영수증 취소대상 tno={} 금액={} — 직접관리 가맹점이면 별도 취소 필요",
                    pgTransactionId, cashReceipt);
        }
        return new CancelResult(true, resCd, parseTime(json.path("canc_time").asText(null)));
    }

    /* ─── 전문(request body) 생성 — 네트워크 없이 검증 가능하도록 분리(package-private) ─── */

    /** 거래등록 전문(모바일). 상품명은 KCP 금지문자를 제거해 보낸다. */
    static Map<String, Object> registerBody(String siteCd, String retUrl, InitCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site_cd", siteCd);
        body.put("ordr_idxx", command.orderId());
        body.put("good_name", sanitize(command.orderName()));
        body.put("good_mny", command.amount());
        body.put("pay_method", "CARD");
        body.put("Ret_URL", retUrl);
        return body;
    }

    /**
     * 승인 전문. ⚠️ {@code ordr_mony}/{@code ordr_no} 는 결제창이 준 값이 아니라 <b>가맹점 DB 원본</b>이다
     * — KCP 가 이 값으로 위변조를 검증한다(문서 명시). 여기 들어오는 command.amount() 는 주문의 권위 금액.
     */
    static Map<String, Object> confirmBody(String siteCd, String certInfo, ConfirmCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site_cd", siteCd);
        body.put("kcp_cert_info", certInfo);
        body.put("enc_data", command.require("enc_data"));
        body.put("enc_info", command.require("enc_info"));
        body.put("tran_cd", command.require("tran_cd"));
        body.put("ordr_mony", command.amount());
        body.put("ordr_no", command.orderId());
        body.put("pay_type", payType(command));
        return body;
    }

    /**
     * 취소 전문. 부분취소({@code STPC})일 때만 {@code mod_mny}(취소액)·{@code rem_mny}(취소 직전 잔액)를 싣는다
     * — KCP 부분취소 필수 전문. 잔액 전부를 취소하면 전체취소({@code STSC}).
     */
    static Map<String, Object> cancelBody(String siteCd, String certInfo, String tno, String signData,
                                          int cancelAmount, int remainingAmount, String reason) {
        String modType = modType(cancelAmount, remainingAmount);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site_cd", siteCd);
        body.put("tno", tno);
        body.put("kcp_cert_info", certInfo);
        body.put("kcp_sign_data", signData);
        body.put("mod_type", modType);
        body.put("mod_desc", sanitize(reason));
        if (!"STSC".equals(modType)) {
            body.put("mod_mny", cancelAmount);
            body.put("rem_mny", remainingAmount);
        }
        return body;
    }

    /**
     * 취소 타입 — 잔액 전부면 전체취소(STSC), 일부면 부분취소(STPC).
     *
     * <p>⚠️ <b>KCP 규칙의 함정(에러 8038)</b>: 여기서 STSC 로 판정하는 "전액 취소"는 <b>그 주문의 첫 취소</b>일 때만
     * 맞다. 이미 <b>부분취소를 시작한 주문</b>은 남은 잔액을 전량 취소하더라도 STSC 가 아니라 <b>STPC</b>
     * ({@code mod_mny=rem_mny})로 보내야 한다(8038 예시: 1만원 중 2천 취소 후 나머지 8천도 {@code mod_mny=8000,
     * rem_mny=8000}).
     *
     * <p>지금은 {@link RefundService} 가 <b>주문당 취소를 1회만</b> 하므로(수강 단위 종료, 재환불은 차단) 이 메서드에
     * 들어오는 취소는 언제나 첫 취소여서 판정이 옳다. <b>회차별 개별 환불을 열면</b> 부분취소 이력 유무
     * ({@code alreadyRefunded > 0})를 인자로 받아 이력이 있으면 STPC 를 강제해야 한다.
     */
    static String modType(int cancelAmount, int remainingAmount) {
        return cancelAmount >= remainingAmount ? "STSC" : "STPC";
    }

    /**
     * 승인 응답의 결제수단을 <b>표시용 한글 라벨</b>로 정규화. KCP 는 같은 "카카오페이"라도 결제 소스에 따라
     * 전문이 <b>세 갈래</b>로 갈려서, 그대로 저장하면 사용자에게 {@code PACA} 같은 코드가 노출된다:
     *
     * <ul>
     *   <li><b>카드형</b> — {@code pay_method=PACA} + {@code card_other_pay_type}(OT13 카카오페이, OT16 네이버페이,
     *       OT23 토스페이, OT12 페이코, OT01 삼성페이, OT03 SSG페이, OT11 L.PAY, OT21 애플페이)</li>
     *   <li><b>머니형</b> — {@code PAKM}(카카오머니) / {@code PATO}(토스머니) / {@code PASG}(SSG머니)</li>
     *   <li><b>포인트형</b> — {@code PANP}(네이버페이 포인트)</li>
     * </ul>
     *
     * <p>⚠️ <b>금액은 여기서 읽지 않는다</b> — 머니/포인트형은 {@code easypoint_mny}, 카드형은 {@code card_mny} 로
     * 필드가 갈리고 쿠폰·페이코포인트 100% 결제 시 {@code card_mny=0} 이 오기 때문. 금액은 항상 서버 권위값을 쓴다.
     */
    static String methodLabel(JsonNode json) {
        String payMethod = json.path("pay_method").asText("");
        switch (payMethod) {
            case "PAKM": return "카카오머니";
            case "PATO": return "토스머니";
            case "PASG": return "SSG머니";
            case "PANP": return "네이버페이포인트";
            case "PABK": return "계좌이체";
            case "PAMC": return "휴대폰";
            case "PAPT": return "포인트";
            case "PATK": return "상품권";
            default: break;
        }
        if (!"PACA".equals(payMethod)) {
            return payMethod.isBlank() ? null : payMethod; // 미지의 코드는 원문 유지(로그 추적용)
        }
        switch (json.path("card_other_pay_type").asText("")) {
            case "OT12": return "페이코";
            case "OT01": return "삼성페이";
            case "OT03": return "SSG페이";
            case "OT11": return "L.PAY";
            case "OT13": return "카카오페이";
            case "OT16": return "네이버페이";
            case "OT21": return "애플페이";
            case "OT23": return "토스페이";
            default: return "신용카드"; // 제휴 간편결제 없이 카드 직접 결제
        }
    }

    /* ─── 내부 ─── */

    /** 실제 결제수단 — KCP 가 승인 시 검증한다. 결제창이 돌려준 값을 FE 가 실어 보내고, 없으면 카드로 본다. */
    private static String payType(ConfirmCommand command) {
        String v = command.pgPayload() == null ? null : command.pgPayload().get("pay_type");
        return v == null || v.isBlank() ? DEFAULT_PAY_TYPE : v;
    }

    private JsonNode post(String url, Map<String, Object> body, String tag) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)) // PG 망 왕복 — 넉넉히
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("[payment-kcp] {} HTTP {} body={}", tag, res.statusCode(), res.body());
                throw new BadRequestException();
            }
            return objectMapper.readTree(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kcp " + tag + " interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("kcp " + tag + " transport error", e);
        }
    }

    /** {@code site_cd^tno^mod_type} 를 SHA256withRSA 서명 후 base64 — 취소 전문의 kcp_sign_data. */
    private String sign(String plain) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("kcp cancel 서명 실패", e);
        }
    }

    /**
     * PEM 개인키 파싱. KCP 가 발급하는 개인키는 보통 <b>비밀번호로 암호화</b>돼 있어 비밀번호가 있으면
     * {@link EncryptedPrivateKeyInfo} 로 복호화한다. 미설정이면 null(취소 시점에 명시적으로 실패).
     */
    private static PrivateKey parsePrivateKey(String pem, String password) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            PKCS8EncodedKeySpec spec;
            if (password == null || password.isBlank()) {
                spec = new PKCS8EncodedKeySpec(der);
            } else {
                EncryptedPrivateKeyInfo encrypted = new EncryptedPrivateKeyInfo(der);
                spec = encrypted.getKeySpec(SecretKeyFactory.getInstance(encrypted.getAlgName())
                        .generateSecret(new PBEKeySpec(password.toCharArray())));
            }
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            // 부팅 시 즉시 드러나게 — 잘못된 키를 들고 운영에 뜨는 것보다 낫다.
            throw new IllegalStateException("KCP 개인키 파싱 실패 — PEM/비밀번호를 확인하세요", e);
        }
    }

    /** KCP 시각(yyyyMMddHHmmss, KST) → OffsetDateTime. 파싱 실패는 null(표시용 값이라 승인을 막지 않는다). */
    private static OffsetDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, KCP_TIME).atZone(KCP_ZONE).toOffsetDateTime();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** KCP 금지 특수문자 제거 — 상품명/취소사유에 섞이면 거래등록·취소가 거절된다. */
    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll(FORBIDDEN, " ").trim();
    }
}
