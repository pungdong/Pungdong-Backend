package com.diving.pungdong.payment;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이니시스 INIpay PRO <b>전문(request body)</b> 사양 — 외부 호출 없이(hermetic) 돈이 걸린 판단만 고정한다.
 * 실제 HTTP 왕복은 테스트 MID({@code INIpayTest})로 수동 검증(문서 참고).
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. K* 전문 생성 / M* 결제수단 라벨 / V* 검증 거부.
 *
 * <p>여기서 지키는 것: (1) 승인 금액·주문번호는 <b>서버 원본</b>이지 결제창이 준 값이 아니다,
 * (2) P_CHKFAKE 서명 공식(SHA-512), (3) 환불 hashData 의 {@code data} <b>바이트 동일성</b>(SHA-512hex),
 * (4) 부분/전체취소 분기와 confirmPrice(취소 후 잔액), (5) SSRF(승인 호스트 조립) 방어.
 */
class InicisPaymentTransmissionTest {

    private static final String MID = "INIpayTest";
    private static final String HASH_KEY = "3CB8183A4BE283555ACC8363C0360223"; // 테스트 HMAC 해시키
    private static final String API_KEY = "ItEQKi3rY7uvDS8l";                  // 테스트 INIAPI 키
    private static final String CLIENT_IP = "127.0.0.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* ─── K* 전문 생성 ─── */

    @Test
    @DisplayName("K1 승인 전문의 금액·주문번호는 서버 권위값이 실린다 — 결제창이 준 값을 쓰지 않는다")
    void payApplBodyUsesServerAuthoritativeAmount() {
        // pgPayload 에 공격자가 다른 금액을 섞어도 전문엔 command.amount() 가 실린다
        var command = new PaymentGateway.ConfirmCommand("rnd-7-abc", 365_000, Map.of(
                "P_AUTH_TID", "auth-xyz", "P_IDCNAME", "fc", "P_AMT", "1000"));

        String body = InicisPaymentGateway.payApplBody(MID, command);

        assertThat(body).contains("P_MID=INIpayTest")
                .contains("P_AUTH_TID=auth-xyz")
                .contains("P_AMT=365000")       // 주문 권위 금액 — 결제창의 1000 이 아님
                .doesNotContain("P_AMT=1000")
                .contains("P_CHARSET=UTF-8");
    }

    @Test
    @DisplayName("K2 P_CHKFAKE 는 Base64(SHA-512(P_AMT + P_OID + P_TIMESTAMP + hashKey)) 다")
    void chkfakeFollowsFormula() throws Exception {
        String amt = "200000", oid = "rnd-1-x", ts = "1712345678901";
        String expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-512").digest((amt + oid + ts + HASH_KEY).getBytes(StandardCharsets.UTF_8)));

        assertThat(InicisPaymentGateway.chkfake(amt, oid, ts, HASH_KEY)).isEqualTo(expected);
    }

    @Test
    @DisplayName("K3 전체취소면 type=refund + price/confirmPrice 없음, hashData 는 body 의 data 와 바이트 동일")
    void fullRefundBodyAndHashByteIdentity() throws Exception {
        var data = InicisPaymentGateway.refundData("25536002322422", 365_000, 365_000, "수강 환불");
        String ts = "20260807120000";

        String body = InicisPaymentGateway.refundBody(API_KEY, MID, CLIENT_IP, ts, "refund", data, MAPPER);

        // 해시에 쓴 dataJson 과 body 의 data 가 바이트 동일해야 이니시스 재계산과 맞는다
        String dataJson = MAPPER.writeValueAsString(data);
        String expectedHash = sha512hex(API_KEY + MID + "refund" + ts + dataJson);
        assertThat(body).contains(dataJson)                       // body 에 그 data 문자열이 그대로 들어감
                .contains("\"hashData\":\"" + expectedHash + "\"")
                .contains("\"type\":\"refund\"")
                .doesNotContain("price").doesNotContain("confirmPrice");
    }

    @Test
    @DisplayName("K4 부분취소면 type=partialRefund + price(취소액)·confirmPrice(취소 후 잔액)")
    void partialRefundCarriesAmounts() throws Exception {
        // 365,000 중 100,000 취소 → 잔액 265,000
        var data = InicisPaymentGateway.refundData("25536002322422", 100_000, 365_000, "부분 환불");
        String ts = "20260807120000";

        String body = InicisPaymentGateway.refundBody(API_KEY, MID, CLIENT_IP, ts, "partialRefund", data, MAPPER);

        assertThat(body).contains("\"type\":\"partialRefund\"")
                .contains("\"price\":\"100000\"")
                .contains("\"confirmPrice\":\"265000\"")   // 취소 후 잔액 = 365000 - 100000
                .contains("\"currency\":\"WON\"");
        // hashData 바이트 동일성 — 부분취소 data 에도 동일 불변식
        String dataJson = MAPPER.writeValueAsString(data);
        assertThat(body).contains("\"hashData\":\"" + sha512hex(API_KEY + MID + "partialRefund" + ts + dataJson) + "\"");
    }

    @Test
    @DisplayName("K7 부분취소 이력 있는 원거래의 잔액 전액 — 우리 원장(원금>잔액)으로 판별해 partialRefund(price=잔액, confirmPrice=0) 전문 (refund 는 500624 거절)")
    void forcedPartialForRemainingAfterPriorPartial() throws Exception {
        // staging 2026-08-15: 427,000 결제 → 233,334 부분취소 → 잔액 193,666 을 refund(전체취소)로 보내니 500624
        // "부분취소 원거래 취소불가". 원금(427,000) > 잔액(193,666) = 취소 이력 있음 → 처음부터 partialRefund 로.
        assertThat(InicisPaymentGateway.refundType(193_666, 193_666, 427_000)).isEqualTo("partialRefund");
        var data = InicisPaymentGateway.refundData("INIproCARDINIpayTest20260815140435526715", 193_666, 193_666, "운영자 수동 환불", true);
        String body = InicisPaymentGateway.refundBody(API_KEY, MID, CLIENT_IP, "20260815205100", "partialRefund", data, MAPPER);
        assertThat(body).contains("\"type\":\"partialRefund\"")
                .contains("\"price\":\"193666\"")
                .contains("\"confirmPrice\":\"0\"");
    }

    @Test
    @DisplayName("K5 취소 타입 판정 — 취소 이력 없는 거래의 전액만 refund, 일부·이력 있는 거래의 잔액 전액은 partialRefund")
    void refundTypeBoundary() {
        assertThat(InicisPaymentGateway.refundType(365_000, 365_000, 365_000)).isEqualTo("refund");        // 이력 없음 + 전액
        assertThat(InicisPaymentGateway.refundType(100_000, 365_000, 365_000)).isEqualTo("partialRefund"); // 일부
        assertThat(InicisPaymentGateway.refundType(265_000, 265_000, 365_000)).isEqualTo("partialRefund"); // 이력 있음 + 잔액 전액
    }

    /* ─── K* 승인 응답 검증(verifyApproval) — 승인엔 서명이 없어 금액 대조가 유일한 방어선 ─── */

    @Test
    @DisplayName("K6 승인 응답 금액이 서버 권위 금액과 다르면 승인 거부 — 로그만 남기고 통과하지 않는다")
    void approvalAmountMismatchRejected() {
        var command = new PaymentGateway.ConfirmCommand("rnd-7-abc", 365_000, Map.of());
        // 이니시스가 다른 금액(1000)을 승인해 돌려줘도 우리 권위 금액(365000)과 다르면 거부
        Map<String, String> res = Map.of("P_STATUS", "00", "P_AMT", "1000", "P_TID", "tid-1");

        assertThatThrownBy(() -> InicisPaymentGateway.verifyApproval(res, command))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("K7 P_AMT 가 없거나 숫자가 아니면 '일치' 로 떨어지지 않고 거부 — 폴백이 command.amount() 이면 대조가 무력화된다")
    void approvalMissingAmountRejected() {
        var command = new PaymentGateway.ConfirmCommand("rnd-7-abc", 365_000, Map.of());

        assertThatThrownBy(() -> InicisPaymentGateway.verifyApproval(
                Map.of("P_STATUS", "00", "P_TID", "tid-1"), command))   // P_AMT 부재
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> InicisPaymentGateway.verifyApproval(
                Map.of("P_STATUS", "00", "P_AMT", "삼십육만오천", "P_TID", "tid-1"), command)) // 파싱 불가
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("K8 P_STATUS=00 + 금액 일치면 승인 성립 — tid(P_TID) 를 취소 식별자로 싣는다")
    void approvalSucceedsWhenStatusOkAndAmountMatches() {
        var command = new PaymentGateway.ConfirmCommand("rnd-7-abc", 365_000, Map.of());
        Map<String, String> res = Map.of("P_STATUS", "00", "P_AMT", "365000", "P_TID", "tid-1", "P_TYPE", "CARD");

        PaymentGateway.ConfirmResult result = InicisPaymentGateway.verifyApproval(res, command);

        assertThat(result.approved()).isTrue();
        assertThat(result.pgTransactionId()).isEqualTo("tid-1");
        assertThat(result.method()).isEqualTo("카드");
    }

    @Test
    @DisplayName("K9 P_STATUS 가 00 이 아니면(인증 실패) 승인 거부")
    void approvalRejectedWhenStatusNotOk() {
        var command = new PaymentGateway.ConfirmCommand("rnd-7-abc", 365_000, Map.of());

        assertThatThrownBy(() -> InicisPaymentGateway.verifyApproval(
                Map.of("P_STATUS", "01", "P_RMESG", "인증 실패", "P_AMT", "365000"), command))
                .isInstanceOf(BadRequestException.class);
    }

    /* ─── M* 결제수단 라벨 ─── */

    @Test
    @DisplayName("M1 P_TYPE → 한글 라벨(CARD 카드 / BANK 계좌이체 / VBANK 가상계좌)")
    void methodLabelByType() {
        assertThat(InicisPaymentGateway.methodLabel(Map.of("P_TYPE", "CARD"))).isEqualTo("카드");
        assertThat(InicisPaymentGateway.methodLabel(Map.of("P_TYPE", "BANK"))).isEqualTo("계좌이체");
        assertThat(InicisPaymentGateway.methodLabel(Map.of("P_TYPE", "VBANK"))).isEqualTo("가상계좌");
    }

    @Test
    @DisplayName("M2 모르는 코드는 원문 유지, 빈 값은 null — 삼켜서 추적 불가가 되지 않게")
    void methodLabelUnknownKept() {
        assertThat(InicisPaymentGateway.methodLabel(Map.of("P_TYPE", "PZZZ"))).isEqualTo("PZZZ");
        assertThat(InicisPaymentGateway.methodLabel(Map.of())).isNull();
    }

    /* ─── V* 검증 거부 ─── */

    @Test
    @DisplayName("V1 승인에 필요한 P_AUTH_TID 가 없으면 400 — 전문을 만들지 않는다")
    void missingAuthTidRejected() {
        var command = new PaymentGateway.ConfirmCommand("o1", 1000, Map.of("P_IDCNAME", "fc")); // P_AUTH_TID 없음

        assertThatThrownBy(() -> InicisPaymentGateway.payApplBody(MID, command))
                .isInstanceOf(com.diving.pungdong.global.advice.exception.BadRequestException.class);
    }

    @Test
    @DisplayName("V2 승인 호스트는 소문자 토큰만 — evil.com/ 같은 호스트 주입(SSRF)은 400")
    void idcHostRejectsInjection() {
        assertThat(InicisPaymentGateway.idcHost("fc")).isEqualTo("fcpaypro.inicis.com");
        assertThat(InicisPaymentGateway.idcHost("stg")).isEqualTo("stgpaypro.inicis.com");
        for (String evil : new String[]{"evil.com/", "fc.evil", "FC", "fc:8080", "", "../x"}) {
            assertThatThrownBy(() -> InicisPaymentGateway.idcHost(evil))
                    .as("idcName=%s", evil)
                    .isInstanceOf(com.diving.pungdong.global.advice.exception.BadRequestException.class);
        }
    }

    private static String sha512hex(String plain) throws Exception {
        return String.format("%0128x", new BigInteger(1,
                MessageDigest.getInstance("SHA-512").digest(plain.getBytes(StandardCharsets.UTF_8))));
    }
}
