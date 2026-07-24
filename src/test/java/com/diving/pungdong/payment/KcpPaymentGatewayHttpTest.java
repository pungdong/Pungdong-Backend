package com.diving.pungdong.payment;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KCP 어댑터의 <b>HTTP 왕복</b> 사양 — 실제 KCP 대신 <b>로컬 스텁 서버</b>(임의 포트)를 띄워 검증한다.
 * 외부 네트워크를 타지 않으므로 hermetic 원칙({@code docs/architecture/testing.md})을 지킨다.
 *
 * <p>{@link KcpPaymentTransmissionTest} 가 "전문을 어떻게 만드는가"를 덮는다면, 여기는 <b>실제로 나가는 바이트와
 * 돌아온 응답의 해석</b>을 덮는다 — 직렬화·응답 파싱·오류 처리·RSA 서명까지.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. H* HTTP 왕복.
 *
 * <p>인증서/개인키는 <b>테스트에서 생성한 키페어</b>를 쓴다 — 실 KCP 자격증명 없이 서명 경로를 검증하기 위해.
 */
class KcpPaymentGatewayHttpTest {

    private static final String SITE = "T0000";
    private static final String CERT = "-----BEGIN CERTIFICATE-----AAAA-----END CERTIFICATE-----";
    private static final String RET_URL = "https://api.plop.cool/payments/kcp/return";

    private HttpServer server;
    private KcpPaymentGateway gateway;
    private KeyPair keyPair;

    /** 경로별 마지막 요청 본문 + 히트 수. */
    private final AtomicReference<String> registerBody = new AtomicReference<>();
    private final AtomicReference<String> approvalBody = new AtomicReference<>();
    private final AtomicReference<String> cancelBody = new AtomicReference<>();
    private final AtomicInteger registerHits = new AtomicInteger();

    private final AtomicReference<String> registerRes = new AtomicReference<>(
            "{\"Code\":\"0000\",\"Message\":\"Success\",\"approvalKey\":\"AKEY\",\"PayUrl\":\"https://kcp/pay\",\"traceNo\":\"TR1\"}");
    private final AtomicReference<String> approvalRes = new AtomicReference<>(
            "{\"res_cd\":\"0000\",\"res_msg\":\"정상처리\",\"tno\":\"25536002322422\",\"amount\":365000,"
                    + "\"pay_method\":\"PACA\",\"card_other_pay_type\":\"OT13\",\"app_time\":\"20260101235959\"}");
    private final AtomicReference<String> cancelRes = new AtomicReference<>(
            "{\"res_cd\":\"0000\",\"res_msg\":\"정상처리\",\"tno\":\"25536002322422\",\"canc_time\":\"20260102101112\"}");
    private final AtomicInteger approvalStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/register", ex -> {
            registerHits.incrementAndGet();
            registerBody.set(read(ex.getRequestBody().readAllBytes()));
            respond(ex, 200, registerRes.get());
        });
        server.createContext("/approval", ex -> {
            approvalBody.set(read(ex.getRequestBody().readAllBytes()));
            respond(ex, approvalStatus.get(), approvalRes.get());
        });
        server.createContext("/cancel", ex -> {
            cancelBody.set(read(ex.getRequestBody().readAllBytes()));
            respond(ex, 200, cancelRes.get());
        });
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        gateway = new KcpPaymentGateway(SITE, CERT, pkcs8Pem(keyPair), "", RET_URL,
                base + "/register", base + "/approval", base + "/cancel", new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /* ─── H* HTTP 왕복 ─── */

    @Test
    @DisplayName("H1 승인 성공 응답을 해석한다 — 취소 식별자(tno)·결제수단 라벨·승인시각(KST)")
    void confirmParsesSuccess() {
        var result = gateway.confirm(new PaymentGateway.ConfirmCommand("rnd-7-abc", 365_000,
                Map.of("enc_data", "ENC", "enc_info", "INFO", "tran_cd", "00100000")));

        assertThat(result.approved()).isTrue();
        assertThat(result.pgTransactionId()).isEqualTo("25536002322422"); // 이후 취소에 쓰인다
        assertThat(result.method()).isEqualTo("카카오페이");               // PACA + OT13
        assertThat(result.approvedAt()).isNotNull();
        assertThat(result.approvedAt().toString()).startsWith("2026-01-01T23:59:59+09:00"); // KCP 시각은 KST
    }

    @Test
    @DisplayName("H2 승인 전문에 서버 권위 금액·주문번호가 실려 나간다 — 실제 전송 바이트로 확인")
    void confirmSendsAuthoritativeValues() throws Exception {
        gateway.confirm(new PaymentGateway.ConfirmCommand("rnd-7-abc", 365_000,
                Map.of("enc_data", "ENC", "enc_info", "INFO", "tran_cd", "00100000")));

        JsonNode sent = new ObjectMapper().readTree(approvalBody.get());
        assertThat(sent.path("ordr_mony").asInt()).isEqualTo(365_000);
        assertThat(sent.path("ordr_no").asText()).isEqualTo("rnd-7-abc");
        assertThat(sent.path("site_cd").asText()).isEqualTo(SITE);
        assertThat(sent.path("kcp_cert_info").asText()).isEqualTo(CERT);
        assertThat(sent.path("pay_type").asText()).isEqualTo("PACA");
    }

    @Test
    @DisplayName("H3 승인이 거절되면(res_cd≠0000) 400 — 성공으로 오해하지 않는다")
    void confirmRejectsNonZeroResCd() {
        approvalRes.set("{\"res_cd\":\"8059\",\"res_msg\":\"결제금액 불일치\"}");

        assertThatThrownBy(() -> gateway.confirm(new PaymentGateway.ConfirmCommand("o1", 1000,
                Map.of("enc_data", "E", "enc_info", "I", "tran_cd", "T"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("H4 승인이 HTTP 오류로 끝나도 400 — 응답 본문이 없어도 삼키지 않는다")
    void confirmRejectsHttpError() {
        approvalStatus.set(500);
        approvalRes.set("{}");

        assertThatThrownBy(() -> gateway.confirm(new PaymentGateway.ConfirmCommand("o1", 1000,
                Map.of("enc_data", "E", "enc_info", "I", "tran_cd", "T"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("H5 모바일 prepare 는 거래등록을 호출하고 결제창 구동값을 돌려준다")
    void mobileInitCallsRegister() {
        Map<String, String> params = gateway.initParams(
                new PaymentGateway.InitCommand("rnd-1-x", "프리다이빙 입문 (1회차)", 200_000, "cust-1", true));

        assertThat(registerHits.get()).isEqualTo(1);
        assertThat(params).containsEntry("approvalKey", "AKEY")
                .containsEntry("payUrl", "https://kcp/pay")
                .containsEntry("traceNo", "TR1")
                .containsEntry("siteCd", SITE)
                .containsEntry("customerKey", "cust-1")
                .containsEntry("retUrl", RET_URL); // 서버 고정값 — 클라이언트가 못 정한다
    }

    @Test
    @DisplayName("H6 PC prepare 는 거래등록을 아예 호출하지 않는다 — KCP 규약(PC 는 거래등록 없음)")
    void pcInitSkipsRegister() {
        Map<String, String> params = gateway.initParams(
                new PaymentGateway.InitCommand("rnd-1-x", "코스", 200_000, "cust-1", false));

        assertThat(registerHits.get()).isZero();
        assertThat(params).containsKeys("siteCd", "payMethod", "retUrl", "customerKey")
                .doesNotContainKeys("approvalKey", "payUrl", "traceNo");
    }

    @Test
    @DisplayName("H7 거래등록이 거절되면(Code≠0000) 400 — 빈 결제창 구동값을 내려보내지 않는다")
    void registerFailureRejected() {
        registerRes.set("{\"Code\":\"M112\",\"Message\":\"필수값 오류\"}");

        assertThatThrownBy(() -> gateway.initParams(
                new PaymentGateway.InitCommand("rnd-1-x", "코스", 200_000, "cust-1", true)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("H8 부분취소 전문의 kcp_sign_data 가 site_cd^tno^mod_type 에 대한 유효한 RSA 서명이다")
    void cancelSignatureIsValid() throws Exception {
        var result = gateway.cancel("25536002322422", 100_000, 220_000, "수강 환불");

        assertThat(result.canceled()).isTrue();
        JsonNode sent = new ObjectMapper().readTree(cancelBody.get());
        assertThat(sent.path("mod_type").asText()).isEqualTo("STPC");   // 잔액 일부 → 부분취소
        assertThat(sent.path("mod_mny").asInt()).isEqualTo(100_000);
        assertThat(sent.path("rem_mny").asInt()).isEqualTo(220_000);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((SITE + "^25536002322422^STPC").getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(sent.path("kcp_sign_data").asText())))
                .as("서명 평문 규칙이 KCP 문서(site_cd^tno^mod_type)와 일치해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("H9 전액취소는 STSC 로 나가고 서명 평문도 STSC 로 맞춰진다")
    void fullCancelSignsWithStsc() throws Exception {
        gateway.cancel("25536002322422", 220_000, 220_000, "수강 환불");

        JsonNode sent = new ObjectMapper().readTree(cancelBody.get());
        assertThat(sent.path("mod_type").asText()).isEqualTo("STSC");
        assertThat(sent.has("mod_mny")).isFalse();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((SITE + "^25536002322422^STSC").getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(sent.path("kcp_sign_data").asText()))).isTrue();
    }

    @Test
    @DisplayName("H10 개인키가 없으면 취소는 조용히 실패하지 않고 즉시 터진다")
    void cancelWithoutPrivateKeyFails() {
        String base = "http://localhost:" + server.getAddress().getPort();
        var noKey = new KcpPaymentGateway(SITE, CERT, "", "", RET_URL,
                base + "/register", base + "/approval", base + "/cancel", new ObjectMapper());

        assertThatThrownBy(() -> noKey.cancel("T1", 1000, 2000, "환불"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("개인키");
    }

    @Test
    @DisplayName("H11 잘못된 개인키 PEM 은 부팅 시점에 터진다 — 잘못된 키를 들고 운영에 뜨지 않게")
    void malformedPrivateKeyFailsFast() {
        String base = "http://localhost:" + server.getAddress().getPort();

        assertThatThrownBy(() -> new KcpPaymentGateway(SITE, CERT, "-----BEGIN PRIVATE KEY-----zzzz-----END PRIVATE KEY-----",
                "", RET_URL, base + "/register", base + "/approval", base + "/cancel", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class);
    }

    /* ─── helpers ─── */

    private static String read(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** 생성한 개인키를 KCP 가 주는 형태(PKCS#8 PEM)로 직렬화. */
    private static String pkcs8Pem(KeyPair kp) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }
}
